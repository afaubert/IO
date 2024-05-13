/*-
 * #%L
 * IO plugin for Fiji.
 * %%
 * Copyright (C) 2008 - 2023 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package sc.fiji.io;

// (c) Gregory Jefferis 2007
// Department of Zoology, University of Cambridge
// jefferis@gmail.com
// All rights reserved
// Source code released under Lesser GNU Public License v2

/* v0.1 2007-04-02 - Gregory Jefferis
 * First functional version can write single channel image (stack)
   to raw/gzip encoded monolithic NRRD file
 * Writes key spatial calibration information	including
   spacings, centers, units, axis mins

 * v0.2 2024-04 - Andre Faubert
 * Support for multichannel images, time data
 * GUI for additional header data and the option to write detached headers
 *
 * Aspects which were NOT implemented:
 * RGB data type
 * NRRD measurement frames
 */

import ij.IJ;
import ij.ImagePlus;
import ij.VirtualStack;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.io.FileInfo;
import ij.io.SaveDialog;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;


/**
 * ImageJ plugin to write a file in Gordon Kindlmann's NRRD
 * or 'nearly raw raster data' format, a simple format which handles
 * coordinate systems and data types in a very general way.
 * This class can be used to create detached headers for other file types.
 * @see <a href="http://teem.sourceforge.net/nrrd">http://teem.sourceforge.net/nrrd</a>
 * and <a href="http://flybrain.stanford.edu/nrrd">http://flybrain.stanford.edu/nrrd</a>
 * @author Andre Faubert 2024-04
 * @author Gregory Jefferis 2007
 */
public class NrrdWriter implements PlugIn {
    // TODO: A more helpful help page. This just describes the specification, not the fields on the GUI.
    private static final String HELP_URL = "https://teem.sourceforge.net/nrrd/format.html";

    // The "choice" value representing null.
    private static final String NONE = "none";

    private static final String[] SPACES_3D = new String[] {
            NONE,
            "3D-right-handed",
            "3D-left-handed",
            "scanner-xyz",
            "RAS",
            "LAS",
            "LPS"
    };
    private static final String[] SPACES_4D = new String[] {
            NONE,
            "3D-right-handed-time",
            "3D-left-handed-time",
            "scanner-xyz-time",
            "RAST",
            "LAST",
            "LPST"
    };

    // Save the previous custom fields area and reuse it if there was an error while interpreting it.
    protected static String customFieldsArea = null;
    protected static boolean recoverCustomFields = false;

    public static final int FILE_LIMIT_FOR_PROMPT = 1000;
    private boolean promptBeforeOverwrite;

    /**
     * The main plugin entry point. Open a dialog to receive header info from the user
     * and write an NRRD file with the name specified.
     * @param arg   The argument passed by IJ.runPlugIn containing the file name to write to.
     * @see         IJ#runPlugIn(String className, String arg)
     */
    public void run(String arg) {
        ImagePlus imp = WindowManager.getCurrentImage();
        if (imp == null) {
            IJ.noImage();
            return;
        }

        String name = arg;
        if (name == null || name.isEmpty())
            name = imp.getTitle();

        FileInfo fiOrig = imp.getOriginalFileInfo();
        NrrdFileInfo fi = fiOrig instanceof NrrdFileInfo ? (NrrdFileInfo) fiOrig : new NrrdFileInfo(fiOrig);
        try {
            fi.fromImagePlus(imp);
        } catch (NrrdException e) {
            e.report();
            return;
        }

        if (IJ.debugMode) IJ.log("NrrdFileInfo before mainDialog: " + fi);
        boolean dialogAborted = mainDialog(fi);
        if (IJ.debugMode) IJ.log("NrrdFileInfo after mainDialog: " + fi);
        if (dialogAborted) return;

        String extension = fi.headless ? ".nhdr" : ".nrrd";
        SaveDialog sd = new SaveDialog("Save NRRD As...", name, extension);
        String fileName = sd.getFileName();
        if (fileName == null) return;
        fi.fileName = fileName;
        fi.directory = sd.getDirectory();

        save(fi);
        imp.setFileInfo(fi);
        imp.setTitle(fi.fileName);
    }

    /**
     * Display the main dialog for an NrrdFileInfo, and allow the user to change its parameters.
     * Field values will be written back to the NrrdFileInfo unless the dialog is cancelled.
     * @return      True when the dialog is cancelled, or otherwise fails to complete.
     */
    public static boolean mainDialog(NrrdFileInfo fi) {
        GenericDialog gd = new GenericDialog("Save NRRD As...");
        gd.addStringField("Contents", strNullSafe(fi.info), 45);
        final String[] encodings = new String[] {"Raw", "Gzip"};
        int defaultEncodingIndex = fi.compression == NrrdFileInfo.GZIP ? 1 : 0;
        gd.addChoice("Encoding", encodings, encodings[defaultEncodingIndex]);
        gd.addCheckbox("Detached header", fi.headless);
        gd.addCheckbox("Little-endian", fi.intelByteOrder);
        String[] spaces;
        if (fi.spaceDim == 3) spaces = SPACES_3D;
        else if (fi.spaceDim == 4) spaces = SPACES_4D;
        else spaces = new String[] {NONE};
        gd.addChoice("Space", spaces, spaces[0]);
        gd.addStringField("Original sample unit", strNullSafe(fi.valueUnit), 24);
        gd.addNumericField("Original min.", fi.valueMinOld, 0, 14, "original sample unit");
        gd.addNumericField("Original max.", fi.valueMaxOld, 0, 14, "original sample unit");
        gd.addNumericField("Display min.", fi.valueMin, 0, 14, null);
        gd.addNumericField("Display max.", fi.valueMax, 0, 14, null);
        gd.addMessage("Custom fields");
        String customFields = recoverCustomFields ? customFieldsArea : fi.customFieldsBlock();
        gd.addTextAreas(customFields + "SCROLLBARS_VERTICAL_ONLY", null, 8, 50);
        if (fi.nChannels > 1) gd.addButton("Channel Settings", new AxisDialog(fi, NrrdAxis.CHANNEL));
        gd.addButton("X-Axis Settings", new AxisDialog(fi, NrrdAxis.X));
        if (fi.height > 1) gd.addButton("Y-Axis Settings", new AxisDialog(fi, NrrdAxis.Y));
        if (fi.nSlices > 1) gd.addButton("Z-Axis Settings", new AxisDialog(fi, NrrdAxis.Z));
        if (fi.nFrames > 1) gd.addButton("Time Settings", new AxisDialog(fi, NrrdAxis.TIME));
        gd.addHelp(HELP_URL);
        gd.setOKLabel("Select file");
        gd.showDialog();

        if (gd.wasCanceled()) return true;

        fi.info = noneToNull(gd.getNextString());
        fi.compression = gd.getNextChoiceIndex() == 1 ? NrrdFileInfo.GZIP : NrrdFileInfo.RAW;
        fi.headless = gd.getNextBoolean();
        fi.intelByteOrder = gd.getNextBoolean();
        fi.space = noneToNull(gd.getNextChoice());
        fi.valueUnit = noneToNull(gd.getNextString());
        fi.valueMinOld = gd.getNextNumber();  // Already NaN if undefined.
        fi.valueMaxOld = gd.getNextNumber();
        fi.valueMin = gd.getNextNumber();
        fi.valueMax = gd.getNextNumber();

        customFieldsArea = gd.getNextText();
        recoverCustomFields = false;
        try {
            fi.parseCustomFields(customFieldsArea);
        } catch (NrrdException e) {
            recoverCustomFields = true;
            e.report();
            return mainDialog(fi);  // Try again.
        }
        return false;
    }

    /**
     * Display a pop-up dialog asking the user if they want to overwrite this file and subsequent ones.
     * If the user has previously accepted, ignore this method call.
     * @param fileName  The file name to refer to in the prompt.
     * @return  true when the operation is cancelled, false to proceed.
     */
    public boolean warnOverwrite(String fileName) {
        if (!promptBeforeOverwrite) return false;
        GenericDialog gd = new GenericDialog("Overwrite File(s)?");
        gd.addMessage("Are you sure you wish to overwrite " + fileName + " and any subsequent files?");
        gd.setOKLabel("Yes to all");
        gd.showDialog();
        if (!gd.wasCanceled()) {
            promptBeforeOverwrite = false;
            return false;
        }
        return true;
    }

    /**
     * Try to save the files represented by a file info object, prompting the user to resolve any issues.
     * The user will be prompted if they try to write a large number of files, or when they attempt
     * to overwrite file without previously approving such action.
     */
    public void save(NrrdFileInfo fi) {
        // Count the files.
        int nFiles = 1;
        for (NrrdAxis axis : fi.axes)
            if (axis.slice) nFiles *= axis.size;
        if (fi.headless) nFiles *= 2;
        // Single file overwrite checking was already handled by SaveDialog.
        promptBeforeOverwrite = nFiles > 1;
        if (nFiles > FILE_LIMIT_FOR_PROMPT) {
            GenericDialog gd = new GenericDialog("Save " + FILE_LIMIT_FOR_PROMPT + "+ files?");
            gd.addMessage("Are you sure you wish to save " + nFiles + " separate files?");
            gd.setOKLabel("Yes");
            gd.showDialog();
            if (gd.wasCanceled()) return;
        }

        // TODO: Does this error handling work well?
        try {
            if (saveSlices(fi, 0)) return;
            fi.imageSaved = true;
            IJ.showStatus("Saved " + fi.fileName);
        } catch (IOException e) {
            new NrrdException("An error occurred when writing the file: " + e).report();
        } catch (NrrdException e) {
            e.report();
        }
        IJ.showProgress(1.0);
    }

    /**
     * Recursively iterate over the axes of the NRRD, saving slices, if necessary.
     * @param fi    The file info from which to write slices. Contains the axis slicing information.
     * @param iAxis The axis index to consider for this depth of recursion.
     * @return      true when the operation is cancelled.
     * @throws IOException      When writing fails for file-related reasons.
     * @throws NrrdException    When writing cannot be performed due to an invalid file info.
     */
    private boolean saveSlices(NrrdFileInfo fi, int iAxis) throws IOException, NrrdException {
        if (iAxis == fi.axes.length) {
            // This is a little odd, but we need the file name to be formatted to match the header name,
            // so we save, edit, and restore it here.
            String originalFileName = fi.fileName;
            fi.fileName = fi.formatSliceFileName();
            try {
                return writeNrrd(fi);
            } finally {
                fi.fileName = originalFileName;
            }
        }

        // At each level of recursion, the next axis is processed.
        NrrdAxis axis = fi.axes[iAxis++];
        if (axis.slice) {
            for (int i = 0; i < axis.size; ++i) {
                axis.sliceIndex = i;
                if (saveSlices(fi, iAxis)) return true;
            }
        } else {
            return saveSlices(fi, iAxis);
        }
        return false;
    }

    /**
     * Write an NRRD file and its header, if necessary.
     * @param fi                The file info to write from.
     * @return                  true when the file exists and cannot be overwritten, so cancel the operation.
     * @throws IOException      When writing fails for file-related reasons.
     * @throws NrrdException    When writing cannot be performed due to an invalid file info.
     */
    boolean writeNrrd(NrrdFileInfo fi) throws IOException, NrrdException {
        File mainFile = new File(fi.directory, fi.fileName);
        if (mainFile.exists() && warnOverwrite(mainFile.getName())) return true;
        OutputStream outMain = Files.newOutputStream(mainFile.toPath());
        try {
            // First write out the full header
            Writer w = new OutputStreamWriter(outMain);
            w.write(fi.asHeader());
            w.flush();

            // Then the image data
            if (!fi.headless) {
                outMain = writeRasterData(fi, outMain);
            }
        } finally {
            outMain.close();
        }
        // ... or write the image data to a separate file.
        if (fi.headless) {
            File dataFile = new File(fi.directory, fi.dataFileName());
            if (dataFile.exists() && warnOverwrite(dataFile.getName())) return true;
            OutputStream outData = Files.newOutputStream(dataFile.toPath());
            try {
                outData = writeRasterData(fi, outData);
            } finally {
                outData.close();
            }
        }
        return false;
    }

    /**
     * Write the pixel raster data to the output stream.
     * @param fi        The file info to use when constructing the image writer.
     * @param outData   The stream to write image data to.
     * @return          The stream which data was written to, which may be built on top of `outData` for compression.
     * @throws IOException      When writing fails for file-related reasons.
     * @throws NrrdException    When writing cannot be performed due to an invalid file info.
     */
    protected OutputStream writeRasterData(NrrdFileInfo fi, OutputStream outData) throws IOException, NrrdException {
        if (fi.compression == NrrdFileInfo.GZIP)
            outData = new GZIPOutputStream(outData);
        NrrdImageWriter writer = new NrrdImageWriter(fi);
        writer.write(outData);
        return outData;
    }

    /**
     * Convert null strings to empty strings, otherwise, unchanged.
     * Used to encode NRRD fields to text input field values.
     */
    private static String strNullSafe(String s) {
        if (s == null) return "";
        return s;
    }

    /**
     * Convert empty or "none" strings to nulls. Used to decode text input fields to NRRD fields.
     */
    private static String noneToNull(String s) {
        if (s == null || s.isEmpty() || NONE.equalsIgnoreCase(s)) return null;
        return s;
    }

    /**
     * In response to a button press, create a dialog to edit settings for an NRRD axis.
     */
    private static class AxisDialog implements ActionListener {
        // There are other possible values for channel, but most are incompatible with all channel dimensionalities.
        static final String[] KINDS_CHANNEL_ALL = new String[] {
                NONE, "list", "point", "normal", "vector", "covariant-vector"};
        static final String[][] KINDS_CHANNEL_BY_DIMENSION = new String[][] {
                {},  // 0
                {"stub", "scalar"},  // 1
                {"complex", "2-vector"},  // 2
                {"3-color", "RGB-color", "HSV-color", "XYZ-color", "3-vector", "3-gradient", "3-normal",
                        "2d-symmetric-matrix"},  // 3
                {"4-color", "RGBA-color", "4-vector", "quaternion", "2D-matrix", "2D-masked-symmetric-matrix"},  // 4
                {"2D-masked-matrix"},  // 5 - There is an error in the specification
                {"3D-symmetric-matrix"},  // 6
                {"3D-masked-symmetric-matrix"},  // 7
                {},  // 8
                {"3D-matrix"},  // 9
                {"3D-masked-matrix"}  // 10
        };
        static final String[] KINDS_SPACE = new String[] {NONE, "space", "domain"};
        static final String[] KINDS_TIME = new String[] {NONE, "time", "domain"};

        static final String VECTOR_SPLIT_PAT = "\\s*,\\s*";

        final NrrdFileInfo fi;
        final int iAxis;

        // TODO: Write additional documentation for methods.

        AxisDialog(NrrdFileInfo fi, int iAxis) {
            this.fi = fi;
            this.iAxis = iAxis;
        }

        int findAxisIndex() {
            switch (iAxis) {
                case NrrdAxis.CHANNEL: return fi.cAxis;
                case NrrdAxis.X: return fi.xAxis;
                case NrrdAxis.Y: return fi.yAxis;
                case NrrdAxis.Z: return fi.zAxis;
                case NrrdAxis.TIME: return fi.tAxis;
                default: return -1;
            }
        }

        int findSpaceAxisIndex() {
            switch (iAxis) {
                case NrrdAxis.X: return fi.xSpaceAxis;
                case NrrdAxis.Y: return fi.ySpaceAxis;
                case NrrdAxis.Z: return fi.zSpaceAxis;
                case NrrdAxis.TIME: return fi.tSpaceAxis;
                default: return -1;
            }
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            NrrdAxis axis = fi.axes[findAxisIndex()];
            int iSpaceAxis = findSpaceAxisIndex();
            boolean isSpaceAxis = iSpaceAxis > -1;
            GenericDialog gd = new GenericDialog("NRRD " + axis.name() + "-axis");
            gd.addCheckbox("Slice axis", axis.slice);
            String[] kinds;
            if (iAxis == NrrdAxis.CHANNEL) {
                kinds = KINDS_CHANNEL_ALL;
                if (axis.size < KINDS_CHANNEL_BY_DIMENSION.length)
                    kinds = strCatArray(kinds, KINDS_CHANNEL_BY_DIMENSION[axis.size]);
            } else if (iAxis == NrrdAxis.TIME) kinds = KINDS_TIME;
            else kinds = KINDS_SPACE;
            String defaultKind = kinds[1];  // NOT kinds[0]
            int kindIndex = strArrayIndexOf(axis.kind, kinds);  // The cases might not match.
            if (kindIndex > -1) defaultKind = kinds[kindIndex];
            gd.addChoice("Kind", kinds, defaultKind);
            gd.addStringField("Label", strNullSafe(axis.label), 14);
            String defaultUnit = (iSpaceAxis > -1 && fi.spaceUnits != null) ? fi.spaceUnits[iSpaceAxis] : axis.unit;
            gd.addStringField("Unit", strNullSafe(defaultUnit), 14);
            if (isSpaceAxis) {
                StringBuilder defaultSpaceDirections = new StringBuilder();
                if (fi.spaceDirections != null) {
                    for (int i = 0; i < fi.spaceDim; ++i) {
                        if (i > 0) defaultSpaceDirections.append(", ");
                        defaultSpaceDirections.append(fi.spaceDirections[iSpaceAxis][i]);
                    }
                }
                gd.addStringField("Space Directions", defaultSpaceDirections.toString(), 14);
                double defaultOrigin = Double.NaN;
                if (fi.spaceOrigin != null) defaultOrigin = fi.spaceOrigin[iSpaceAxis];
                gd.addNumericField("Space Origin", defaultOrigin, 0, 14, null);
            }
            gd.addNumericField("Spacing", axis.spacing, 0, 14, null);
            gd.addNumericField("Min.", axis.min, 0, 14, null);
            gd.addNumericField("Max.", axis.max, 0, 14, null);
            gd.addNumericField("Thickness", axis.thickness, 0, 14, null);
            gd.addHelp(HELP_URL);
            gd.showDialog();

            if (gd.wasCanceled()) return;
            // TODO: What is smart recording, and should I enable/disable it here?

            axis.slice = gd.getNextBoolean();
            axis.kind = noneToNull(gd.getNextChoice());
            axis.label = noneToNull(gd.getNextString());
            String unit = noneToNull(gd.getNextString());
            if (isSpaceAxis) {
                axis.unit = null;
                if (fi.spaceUnits == null) fi.newSpaceUnits();
                fi.spaceUnits[iSpaceAxis] = unit;

                if (fi.spaceDirections == null) fi.newSpaceDirections(false);
                fi.spaceDirections[iSpaceAxis] = parseVector(fi.spaceDim, gd.getNextString());

                if (fi.spaceOrigin == null) fi.newSpaceOrigin();
                fi.spaceOrigin[iSpaceAxis] = gd.getNextNumber();
            } else {
                axis.unit = unit;
            }

            axis.spacing = gd.getNextNumber();
            axis.min = gd.getNextNumber();
            axis.max = gd.getNextNumber();
            axis.thickness = gd.getNextNumber();
        }

        private static double[] parseVector(int size, String vec) {
            double[] r = new double[size];
            String[] fields = vec.trim().split(VECTOR_SPLIT_PAT);
            for (int i = 0; i < size; ++i) {
                try {
                    // When the i-th field doesn't exist, that can also be NaN.
                    r[i] = Double.parseDouble(fields[i]);
                    if (Double.isInfinite(r[i])) r[i] = Double.NaN;
                } catch (IndexOutOfBoundsException | NumberFormatException e) {
                    r[i] = Double.NaN;
                }
            }
            return r;
        }

        private static int strArrayIndexOf(String test, String[] arr) {
            for (int i = 0; i < arr.length; ++i)
                if (arr[i].equalsIgnoreCase(test)) return i;
            return -1;
        }

        private static String[] strCatArray(String[] a, String[] b) {
            String[] r = new String[a.length + b.length];
            System.arraycopy(a, 0, r, 0, a.length);
            System.arraycopy(b, 0, r, a.length, b.length);
            return r;
        }
    }
}

/**
 * Writes a raw image described by a FileInfo object to an OutputStream.
 * This is a modified version of ImageWriter with two additional abilities:
 * 1. Can reorder pixel data from ImageJ's native format into C, X, Y, Z, T (from fast to slow) where channel values
 * are interleaved.
 * 2. Can save a "slice" of an NRRD image along any set of axes.
 * @author Andre Faubert 2024-04
 * @see ij.io.ImageWriter
 */
class NrrdImageWriter {
    private final NrrdFileInfo fi;
    private int c0, z0, t0;
    private int z1, t1;
    private int nc, nx, ny;
    private final int pixelOffset;
    private boolean showProgressBar = true;
    private boolean useWholeImageBuffer;

    public NrrdImageWriter(NrrdFileInfo fi) {
        this.fi = fi;

        t1 = fi.nFrames;
        if (fi.tAxis > -1 && fi.axes[fi.tAxis].slice) {
            t0 = fi.axes[fi.tAxis].sliceIndex;
            t1 = t0 + 1;
        }
        z1 = fi.nSlices;
        if (fi.zAxis > -1 && fi.axes[fi.zAxis].slice) {
            z0 = fi.axes[fi.zAxis].sliceIndex;
            z1 = z0 + 1;
        }

        nc = fi.nChannels;
        if (fi.cAxis > -1 && fi.axes[fi.cAxis].slice) {
            c0 = fi.axes[fi.cAxis].sliceIndex;
            nc = 1;
        }
        int x0 = 0;
        nx = fi.width;
        if (fi.xAxis > -1 && fi.axes[fi.xAxis].slice) {
            x0 = fi.axes[fi.xAxis].sliceIndex;
            nx = 1;
        }
        int y0 = 0;
        ny = fi.height;
        if (fi.yAxis > -1 && fi.axes[fi.yAxis].slice) {
            y0 = fi.axes[fi.yAxis].sliceIndex;
            ny = 1;
        }
        pixelOffset = x0 + y0*fi.width;
    }

    /**
     * Write an 8-bit array, interleaving the pixel data for each image channel.
     * @param out       The stream to write pixel data to.
     * @param pixels    An array of pixel data arrays, one for each image comprising a stack of channels.
     * @throws IOException  When writing to the output stream fails.
     */
    void write8BitChannelStack(OutputStream out, byte[][] pixels, int si) throws IOException {
        long bytesWritten = 0L;
        long outSize = (long) nc * nx * ny;
        int bufSize = chooseBufferSize(outSize);
        byte[] buffer = new byte[bufSize];
        int ip = pixelOffset;  // Pixel index.

        while (bytesWritten < outSize) {
            if (bytesWritten + bufSize > outSize)
                bufSize = (int) (outSize - bytesWritten);
            for (int i = 0; i < bufSize; ++i) {
                buffer[i] = pixels[si + ip % nc][ip / nc];
                ip += nx == 1 ? fi.width : 1;
            }
            out.write(buffer, 0, bufSize);
            bytesWritten += bufSize;
        }
    }

    /**
     * Write a 16-bit array, interleaving the pixel data for each image channel.
     * @param out       The stream to write pixel data to.
     * @param pixels    An array of pixel data arrays, one for each image comprising a stack of channels.
     * @throws IOException  When writing to the output stream fails.
     */
    void write16BitChannelStack(OutputStream out, short[][] pixels, int si) throws IOException {
        long bytesWritten = 0L;
        long outSize = 2L * nc * nx * ny;
        int bufSize = chooseBufferSize(outSize);
        byte[] buffer = new byte[bufSize];
        int ip = pixelOffset;  // Pixel index.

        while (bytesWritten < outSize) {
            if (bytesWritten + bufSize > outSize)
                bufSize = (int) (outSize - bytesWritten);
            if (fi.intelByteOrder) {
                for (int i = 0; i < bufSize; i += 2) {
                    int value = pixels[si + ip % nc][ip / nc];
                    buffer[i] = (byte) value;
                    buffer[i + 1] = (byte) (value >>> 8);
                    ip += nx == 1 ? fi.width : 1;
                }
            } else {
                for (int i = 0; i < bufSize; i += 2) {
                    int value = pixels[si + ip % nc][ip / nc];
                    buffer[i + 1] = (byte) value;
                    buffer[i] = (byte) (value >>> 8);
                    ip += nx == 1 ? fi.width : 1;
                }
            }
            IJ.log("Writing " + bufSize + " bytes.");
            out.write(buffer, 0, bufSize);
            bytesWritten += bufSize;
        }
    }

    /**
     * Write a float array, interleaving the pixel data for each image channel.
     * @param out       The stream to write pixel data to.
     * @param pixels    An array of pixel data arrays, one for each image comprising a stack of channels.
     * @throws IOException  When writing to the output stream fails.
     */
    void writeFloatChannelStack(OutputStream out, float[][] pixels, int si) throws IOException {
        long bytesWritten = 0L;
        long outSize = 4L * nc * nx * ny;
        int bufSize = chooseBufferSize(outSize);
        byte[] buffer = new byte[bufSize];
        int ip = pixelOffset;  // Pixel index.

        while (bytesWritten < outSize) {
            if (bytesWritten + bufSize > outSize)
                bufSize = (int) (outSize - bytesWritten);
            if (fi.intelByteOrder) {
                for (int i = 0; i < bufSize; i += 4) {
                    int value = Float.floatToRawIntBits(pixels[si + ip % nc][ip / nc]);
                    buffer[i] = (byte) value;
                    buffer[i + 1] = (byte) (value >> 8);
                    buffer[i + 2] = (byte) (value >> 16);
                    buffer[i + 3] = (byte) (value >> 24);
                    ip += nx == 1 ? fi.width : 1;
                }
            } else {
                for (int i = 0; i < bufSize; i += 4) {
                    int value = Float.floatToRawIntBits(pixels[si + ip % nc][ip / nc]);
                    buffer[i + 3] = (byte) value;
                    buffer[i + 2] = (byte) (value >> 8);
                    buffer[i + 1] = (byte) (value >> 16);
                    buffer[i] = (byte) (value >> 24);
                    ip += nx == 1 ? fi.width : 1;
                }
            }
            out.write(buffer, 0, bufSize);
            bytesWritten += bufSize;
        }
    }

    /**
     * Not implemented.
     * @throws IOException  Always.
     */
    void writeRGBChannelStack(OutputStream ignoredOut, int[][] ignoredPixels, int ignoredSi) throws IOException {
        throw new IOException("NrrdImageWriter: RGB pixel data is not supported.");
    }

    /**
     * Decide the pixel buffer size based on the image's size, in bytes.
     * The buffer size should be a multiple of 4 so that full 32-bit values can always fit.
     * @param imageSize The size of images in this stack, in bytes.
     */
    private int chooseBufferSize(long imageSize) {
        if (useWholeImageBuffer || imageSize < 4L) return (int) imageSize;
        int bufSize = (int) (imageSize / 50L);
        if (bufSize < 65536) bufSize = 65536;
        if (bufSize > imageSize) bufSize = (int) imageSize;
        return bufSize / 4 * 4;
    }

    private void showProgress(double progress) {
        // TODO: figure out the progress bar.
        if (showProgressBar) IJ.showProgress(progress);
    }

    /**
     * TODO: Update docs
     * TODO: How did I get a null pointer exception while writing and closing the file written?
     * Infer the type of this pixel data and call the corresponding pixel writer.
     * @param out   The output stream to write to.
     * @param stack The pixel data for a stack of image channels to write. Int32 is treated as RGB.
     */
    void writeImage(OutputStream out, Object[] stack, int si) throws IOException {
        // TODO: Let's not make a whole new copy of the stack each time we want to access it.
        if (IJ.debugMode) IJ.log("writeImage slice " + si + " of pixel type " + stack[0].getClass().getName());
        if (stack[0] instanceof byte[])
            write8BitChannelStack(out, Arrays.copyOf(stack, stack.length, byte[][].class), si);
        else if (stack[0] instanceof short[])
            write16BitChannelStack(out, Arrays.copyOf(stack, stack.length, short[][].class), si);
        else if (stack[0] instanceof float[])
            writeFloatChannelStack(out, Arrays.copyOf(stack, stack.length, float[][].class), si);
        else if (stack[0] instanceof int[])
            writeRGBChannelStack(out, Arrays.copyOf(stack, stack.length, int[][].class), si);
        else throw new IOException("NrrdImageWriter: Unrecognized pixel stack type, " + stack[0].getClass().getName());
    }

    void writeStack(OutputStream out, Object[] stack) throws IOException {
        showProgressBar = false;
        useWholeImageBuffer = true;
        for (int t = t0; t < t1; ++t) {
            for (int z = z0; z < z1; ++z) {
                int i = (t * fi.nSlices + z) * fi.nChannels + c0;
                IJ.showStatus("Writing: " + (i + 1) + "/" + fi.nImages);
                writeImage(out, stack, i);
                IJ.showProgress((double) (i + 1) / fi.nImages);
            }
        }
        IJ.showProgress(1.0);
    }

    void writeVirtualStack(OutputStream out, VirtualStack virtualStack) throws IOException {
        if (IJ.debugMode) IJ.log("writeVirtualStack: " + virtualStack);
        showProgressBar = false;
        useWholeImageBuffer = false;
        Object[] channelStack = new Object[nc];
        for (int t = t0; t < t1; ++t) {
            for (int z = z0; z < z1; ++z) {
                int i = (t * fi.nSlices + z) * fi.nChannels + c0;
                IJ.showStatus("Writing: " + (i + 1) + "/" + fi.nImages);
                for (int j = 0; j < nc; ++j) {
                    ImageProcessor ip = virtualStack.getProcessor(1 + i + j);
                    if (ip == null) throw new IOException("NrrdImageWriter: The virtual stack is empty.");
                    channelStack[j] = ip.getPixels();
                }
                writeImage(out, channelStack, 0);
                IJ.showProgress((double) (i + 1) / fi.nImages);
            }
        }
    }

    /**
     * Writes the images to the specified OutputStream, leaving the OutputStream open.
     * If this is not a virtual stack, the fi.pixels field must contain the image data.
     * If fi.nImages > 1 then fi.pixels must be a 2D array, for example an
     * array of images returned by ImageStack.getImageArray().
     * The fi.offset field is ignored.
     * @param out   The OutputStream to write to. This stream will not be closed.
     * @throws IOException      When writing fails.
     * @throws NrrdException    When the image format is unsupported or the pixel data is missing.
     */
    public void write(OutputStream out) throws IOException, NrrdException {
        if (fi.virtualStack == null) {
            if (fi.pixels == null)
                throw new NrrdException("NrrdImageWriter: fi.pixels == null.");
            if (fi.nImages > 1 && !(fi.pixels instanceof Object[]))
                throw new NrrdException("NrrdImageWriter: fi.pixels isn't a stack.");
        }
        // No need for a progress bar if file size < 25MB.
        if (fi.byteSize() < 26214400L) showProgressBar = false;
        useWholeImageBuffer = false;
        switch (fi.fileType) {
            case NrrdFileInfo.RGB48:
            case NrrdFileInfo.GRAY8:
            case NrrdFileInfo.COLOR8:
            case NrrdFileInfo.GRAY16_SIGNED:
            case NrrdFileInfo.GRAY16_UNSIGNED:
            case NrrdFileInfo.GRAY32_FLOAT:
                if (fi.nImages > 1) {
                    if (fi.virtualStack != null) writeVirtualStack(out, fi.virtualStack);
                    else writeStack(out, (Object[]) fi.pixels);
                } else {
                    Object[] channelStack = new Object[] {fi.pixels};
                    writeImage(out, channelStack, 0);
                }
                break;
            default:
                throw new NrrdException("NrrdImageWriter: Unsupported file type.");
        }
    }
}