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

/* (c) Gregory Jefferis 2007
 * Department of Zoology, University of Cambridge
 * jefferis@gmail.com
 * All rights reserved
 * Source code released under Lesser GNU Public License v2
 */

/* v0.2 2024-04 - Andre Faubert
 * Moved the bulk of the code to NrrdFileInfo.
 * Merged with a modified subset of FileOpener.
 * Support for virtual stacks has not been implemented.
 */

import ij.*;
import ij.gui.GenericDialog;
import ij.io.FileInfo;
import ij.io.FileOpener;
import ij.io.OpenDialog;
import ij.measure.Calibration;
import ij.plugin.PlugIn;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;


/**
 * ImageJ plugin to read a file in Gordon Kindlmann's NRRD
 * or 'nearly raw raster data' format, a simple format which handles
 * coordinate systems and data types in a very general way.
 * @see <a href="http://teem.sourceforge.net/nrrd">http://teem.sourceforge.net/nrrd</a>
 * @see <a href="http://flybrain.stanford.edu/nrrd">http://flybrain.stanford.edu/nrrd</a>
 * @author Andre Faubert 2024-04
 * @author Gregory Jefferis 2007
 */
public class NrrdReader extends ImagePlus implements PlugIn {
    // To indicate the line on which an error occurred.
    private int lineNum = 0;
    private NrrdFileInfo fi;
    private static boolean showCalibrationConflictMessage = true;

    /**
     * The main plugin entry point. Read an NRRD file from the path specified.
     * @param arg   The argument passed by IJ.runPlugIn containing the file path.
     * @see         IJ#runPlugIn(String className, String arg)
     */
    public void run(String arg) {
        String directory, name;
        if (arg == null || arg.isEmpty()) {
            OpenDialog od = new OpenDialog("Import NRRD (or .nhdr) File...", arg);
            name = od.getFileName();
            if (name == null) return;
            directory = od.getDirectory();
        } else {
            File file = new File(arg);
            directory = file.getParent();
            name = file.getName();
        }

        ImagePlus imp = load(directory, name);
        if (imp == null) return;  // Failed to load the file.

        // Copy attributes from the loaded ImagePlus onto this extension.
        setStack(name, imp.getStack());
        copyScale(imp);  // Does nothing when the global scale is set.
        setFileInfo(fi);
        setDimensions(fi.nChannels, fi.nSlices, fi.nFrames);
        if (getStackSize() > 1) setOpenAsHyperStack(true);

        // If the user selected a file, it should be displayed to them.
        if (arg == null || arg.isEmpty()) show();
    }

    // TODO: Write additional documentation for methods.

    public ImagePlus load(String directory, String fileName) {
        directory = IJ.addSeparator(directory);
        if (fileName == null || fileName.isEmpty()) return null;

        IJ.showStatus("Loading NRRD file: " + directory + fileName);
        try {
            parseHeader(directory, fileName);
        } catch (IOException e) {
            // Do not report the line number; it is unlikely to help.
            handleError(new NrrdException(e.getMessage()));
            return null;
        } catch (NrrdException e) {
            handleError(new NrrdException(e.getMessage(), lineNum));
            return null;
        } catch (NumberFormatException e) {
            handleError(new NrrdException("Invalid number", lineNum));
            return null;
        }
        if (IJ.debugMode) IJ.log("NrrdFileInfo: " + fi);
        return openImagePlus();
    }

    private void parseHeader(String directory, String fileName)
            throws IOException, NrrdException, NumberFormatException {
        if (IJ.debugMode) IJ.log("Entering NrrdReader.parseHeader()");

        fi = new NrrdFileInfo();
        fi.directory = directory;
        fi.fileName = fileName;

        // We need a RandomAccessFile to know the file offset.
        try (RandomAccessFile input = new RandomAccessFile(directory + fileName, "r")) {
            // The first line always has the magic number. This has already been checked.
            String line = input.readLine();
            fi.parseVersion(line);
            if (IJ.debugMode) IJ.log("NRRD version: " + fi.headerVersion);
            // Parse the header file until we reach an empty line.
            lineNum = 2;
            while (true) {
                line = input.readLine();
                if (line.trim().isEmpty()) break;
                fi.parseHeaderLine(line);
                lineNum++;
            }
            fi.headerLength = input.getFilePointer();
        }

        fi.verifyMandatoryFields();
        fi.resolveShape();
    }

    private void handleError(NrrdException e) {
        if (fi != null) {
            if (IJ.debugMode) IJ.log("NrrdFileInfo: " + fi);
            e.report(fi.headerVersion);
        } else e.report();
    }

    private ImagePlus openImagePlus() {
        ColorModel cm = createColorModel(fi);
        ImagePlus imp = fi.nImages > 1 ? openStack(cm) : openProcessor(cm);
        if (imp == null) return null;
        Calibration cal = fi.updateCalibration(imp);
        if (IJ.debugMode) IJ.log("Calibration: " + cal);
        imp.setFileInfo(fi);
        if (fi.info != null) imp.setProperty("Info", fi.info);
        if (fi.properties != null) imp.setProperties(fi.properties);
        checkForCalibrationConflict(imp, cal);
        return imp;
    }

    private ImagePlus openProcessor(ColorModel cm) {
        Object[] pixels = null;
        try (InputStream is = fi.makeInputStream()) {
            if (is == null) return null;
            NrrdImageReader reader = new NrrdImageReader(fi);
            pixels = reader.readPixels(is, true);
        } catch (IOException e) {
            handleError(new NrrdException(e.getMessage()));
            return null;
        } catch (Exception e) {
            if (!Macro.MACRO_CANCELED.equals(e.getMessage())) IJ.handleException(e);
        }
        if (pixels == null) {
            if (IJ.debugMode) IJ.log("readPixels failed");
            return null;
        }
        ImageProcessor ip;
        switch (fi.fileType) {
            case NrrdFileInfo.GRAY8:
            case NrrdFileInfo.GRAY8_SIGNED:  // Automatically converted to a short...
                ip = new ByteProcessor(fi.width, fi.height, (byte[]) pixels[0], cm);
                break;
            case NrrdFileInfo.GRAY16_SIGNED:
            case NrrdFileInfo.GRAY16_UNSIGNED:
                ip = new ShortProcessor(fi.width, fi.height, (short[]) pixels[0], cm);
                break;
            case NrrdFileInfo.GRAY32_INT:
            case NrrdFileInfo.GRAY32_UNSIGNED:
            case NrrdFileInfo.GRAY32_FLOAT:
            case NrrdFileInfo.GRAY64_FLOAT:  // Automatically down-cast to float.
                ip = new FloatProcessor(fi.width, fi.height, (float[]) pixels[0], cm);
                break;
            default:
                return null;
        }
        return new ImagePlus(fi.fileName, ip);
    }

    private ImagePlus openStack(ColorModel cm) {
        ImageStack stack = new ImageStack(fi.width, fi.height, cm);
        try (InputStream is = fi.makeInputStream()) {
            if (is == null) return null;
            NrrdImageReader reader = new NrrdImageReader(fi);
            IJ.resetEscape();

            for (int i = 1; i <= fi.nImages; i += fi.nChannels) {
                IJ.showStatus("Reading: " + i + "/" + fi.nImages);
                if (IJ.escapePressed()) {
                    IJ.beep();
                    IJ.showProgress(1.0);
                    return null;
                }
                Object[] pixels = reader.readPixels(is, false);
                if (pixels == null)
                    break;
                for (Object pixel : pixels)
                    stack.addSlice(null, pixel);
                IJ.showProgress(i, fi.nImages);
            }
        } catch (OutOfMemoryError e) {
            IJ.outOfMemory(fi.fileName);
            stack.trim();
        } catch (Exception e) {
            handleError(new NrrdException(e.getMessage()));
        }
        IJ.showProgress(1.0);
        if (stack.size() == 0) return null;
        ImagePlus imp = new ImagePlus(fi.fileName, stack);
        imp.setDimensions(fi.nChannels, fi.nSlices, fi.nFrames);
        imp.setOpenAsHyperStack(true);
        setStackDisplayRange(imp);
        return imp;
    }

    /**
     * Update the display range to be the lowest and highest pixel values in the whole stack.
     * @param imp   The ImagePlus to edit in-place.
     */
    private static void setStackDisplayRange(ImagePlus imp) {
        if (IJ.debugMode) IJ.log("Setting the display range for the whole stack.");
        ImageStack stack = imp.getStack();
        double min = Double.MAX_VALUE;
        double max = -1.7976931348623157e308;
        int n = stack.size();
        for (int i = 1; i <= n; ++i) {
            IJ.showStatus("Calculating stack min and max: " + i + "/" + n);
            ImageProcessor ip = stack.getProcessor(i);
            ip.resetMinAndMax();
            if (ip.getMin() < min) min = ip.getMin();
            if (ip.getMax() > max) max = ip.getMax();
        }
        imp.getProcessor().setMinAndMax(min, max);
    }

    /**
     * Check whether the calibration conflicts with the global calibration. If so, prompt
     * the user with a dialog box to change it.
     * This is a nearly verbatim copy of FileOpener.setShowConflictMessage.
     * @param imp   The image plus from which to retrieve the global calibration.
     * @param cal   The calibration to compare to.
     */
    private static void checkForCalibrationConflict(ImagePlus imp, Calibration cal) {
        Calibration gCal = imp.getGlobalCalibration();
        if (gCal == null || !showCalibrationConflictMessage || IJ.isMacro())
            return;
        if (cal.pixelWidth == gCal.pixelWidth && cal.getUnit().equals(gCal.getUnit()))
            return;
        GenericDialog gd = new GenericDialog(imp.getTitle());
        gd.addMessage("The calibration of this image conflicts\nwith the current global calibration.");
        gd.addCheckbox("Disable_Global Calibration", true);
        gd.addCheckbox("Disable_these Messages", false);
        gd.showDialog();
        if (gd.wasCanceled()) return;
        boolean disable = gd.getNextBoolean();
        if (disable) {
            imp.setGlobalCalibration(null);
            imp.setCalibration(cal);
            WindowManager.repaintImageWindows();
        }
        boolean noShow = gd.getNextBoolean();
        if (noShow) {
            showCalibrationConflictMessage = false;
            FileOpener.setShowConflictMessage(false);
        }
    }

    /**
     * This is a nearly verbatim copy of FileOpener.createColorModel.
     */
    private static ColorModel createColorModel(NrrdFileInfo fi) {
        if (fi.lutSize > 0)
            return new IndexColorModel(8, fi.lutSize, fi.reds, fi.greens, fi.blues);
        return LookUpTable.createGrayscaleColorModel(fi.whiteIsZero);
    }
}

/**
 * Reader for a single multichannel image from a stream with the axis order, CXY, from fast to slow.
 * This is a parred-down version of ImageReader with additional ability to reorder pixel data from
 * CXY order into ImageJ's native format where each channel is a separate image.
 * @author Andre Faubert 2024-04
 * @see ij.io.ImageReader
 */
class NrrdImageReader {
    private final NrrdFileInfo fi;
    private final int nChannels;  // Copied from fi.nChannels
    private final int nPixels;  // The number of pixels in a single image.
    private long byteCount;  // The total number of bytes in the image.
    private int bufferSize;  // How many bytes to try to read in each chunk.
    private boolean showProgressBar = false;
    private long startTime;  // System millisecond time since beginning to read.
    private boolean mustWarnEOF = true;

    public NrrdImageReader(NrrdFileInfo fi) {
        this.fi = fi;
        nChannels = fi.nChannels;
        nPixels = fi.width * fi.height;
    }

    /**
     * An internal helper function for filling a byte buffer from an input stream.
     * @param in        Where to read bytes from to fill the buffer.
     * @param buffer    The buffer to populate with data.
     * @param bytesRead How many bytes have already been read.
     * @return          The new value for `bytesRead`.
     * @throws IOException  When the input stream fails to read.
     */
    long readBuffer(InputStream in, byte[] buffer, long bytesRead) throws IOException {
        if (bytesRead + bufferSize > byteCount)
            bufferSize = (int) (byteCount - bytesRead);

        int chunkSize;
        for (int ib = 0; ib < bufferSize; ib += chunkSize) {
            chunkSize = in.read(buffer, ib, bufferSize - ib);
            if (chunkSize == -1) {
                warnEOF();
                if (IJ.debugMode) IJ.log("EOF while reading " + fi.fileName + ". bytesRead=" + bytesRead);
                // End-of-file error. Use a buffer of zeros instead.
                for (int i = ib; i < bufferSize; ++i)
                    buffer[i] = 0;
                return byteCount;
            }
        }
        return bytesRead + bufferSize;
    }

    /**
     * Reads an 8-bit image with multiple channels in the axis order CXY.
     * Signed 8-bit values are converted to unsigned by adding 0x80, shifting the range of
     * possible values from [-128, 127] to [0, 255].
     * @param in    Where to read image bytes from.
     * @return      An array of image pixel data arrays, one for each channel.
     * @throws IOException  When the input stream fails to read.
     */
    byte[][] read8bitImage(InputStream in) throws IOException {
        byte[] buffer = new byte[bufferSize];
        byte[][] pixels = new byte[nChannels][nPixels];
        long bytesRead = 0L;

        int iv = 0;  // The value index counts how many numbers have been decoded.
        while (bytesRead < byteCount) {
            bytesRead = readBuffer(in, buffer, bytesRead);
            // Decode the pixels from the buffer.
            if (fi.fileType == NrrdFileInfo.GRAY8) {
                for (int ib = 0; ib < bufferSize; ++ib, ++iv)
                    pixels[iv % nChannels][iv / nChannels] = buffer[ib];
            } else {
                // Adding 0x80 is equivalent to flipping the sign bit.
                for (int ib = 0; ib < bufferSize; ++ib, ++iv)
                    pixels[iv % nChannels][iv / nChannels] = (byte) (buffer[ib] + 0x80);
            }
            showProgress(bytesRead, byteCount);
        }
        return pixels;
    }

    /**
     * Reads a 16-bit image with multiple channels in the axis order CXY.
     * Signed pixels are converted to unsigned by adding 0x8000, shifting the range of
     * possible values from [-32768, 32767] to [0, 65535].
     * @param in    Where to read image bytes from.
     * @return      An array of image pixel data arrays, one for each channel.
     * @throws IOException  When the input stream fails to read.
     */
    short[][] read16bitImage(InputStream in) throws IOException {
        byte[] buffer = new byte[bufferSize];
        short[][] pixels = new short[nChannels][nPixels];
        long bytesRead = 0L;

        int iv = 0;  // The value index counts how many numbers have been decoded.
        while (bytesRead < byteCount) {
            bytesRead = readBuffer(in, buffer, bytesRead);

            // Decode the pixels from the buffer.
            // Adding 0x8000 is equivalent to flipping the sign bit.
            // Perhaps surprisingly, we do not need to AND the most significant signed byte with 0xFF
            // when implicitly casting to int32 because the least significant 8 bits keep their values
            // even when the byte bears a negative value. However, the least significant byte must be ANDed.
            if (fi.intelByteOrder) {
                if (fi.fileType == FileInfo.GRAY16_SIGNED) {
                    for (int ib = 0; ib < bufferSize; ib += 2, ++iv)
                        pixels[iv % nChannels][iv / nChannels] =
                                (short) ((buffer[ib + 1] << 8 | buffer[ib] & 0xff) + 0x8000);
                } else {
                    for (int ib = 0; ib < bufferSize; ib += 2, ++iv)
                        pixels[iv % nChannels][iv / nChannels] =
                                (short) (buffer[ib + 1] << 8 | buffer[ib] & 0xff);
                }
            } else {
                if (fi.fileType == FileInfo.GRAY16_SIGNED) {
                    for (int ib = 0; ib < bufferSize; ib += 2, ++iv)
                        pixels[iv % nChannels][iv / nChannels] =
                                (short) ((buffer[ib] << 8 | buffer[ib + 1] & 0xff) + 0x8000);
                } else {
                    for (int ib = 0; ib < bufferSize; ib += 2, ++iv)
                        pixels[iv % nChannels][iv / nChannels] =
                                (short) (buffer[ib] << 8 | buffer[ib + 1] & 0xff);
                }
            }

            showProgress(bytesRead, byteCount);
        }
        return pixels;
    }

    /**
     * Reads a 32-bit image with multiple channels in the axis order CXY.
     * Integer values are cast to floats.
     * @param in    Where to read image bytes from.
     * @return      An array of image pixel data arrays, one for each channel.
     * @throws IOException  When the input stream fails to read.
     */
    float[][] read32bitImage(InputStream in) throws IOException {
        byte[] buffer = new byte[bufferSize];
        float[][] pixels = new float[nChannels][nPixels];
        long bytesRead = 0L;

        int iv = 0;  // The value index counts how many numbers have been decoded.
        while (bytesRead < byteCount) {
            bytesRead = readBuffer(in, buffer, bytesRead);

            // Decode the pixels from the buffer.
            if (fi.intelByteOrder) {
                if (fi.fileType == FileInfo.GRAY32_FLOAT) {
                    for (int ib = 0; ib < bufferSize; ib += 4, ++iv)
                        pixels[iv % nChannels][iv / nChannels] = Float.intBitsToFloat(
                                (buffer[ib + 3] & 0xff) << 24
                                        | (buffer[ib + 2] & 0xff) << 16
                                        | (buffer[ib + 1] & 0xff) << 8
                                        |  buffer[ib] & 0xff);
                } else if (fi.fileType == FileInfo.GRAY32_UNSIGNED) {
                    // Cast to a long to preserve the most significant bit.
                    for (int ib = 0; ib < bufferSize; ib += 4, ++iv)
                        pixels[iv % nChannels][iv / nChannels] = (float) (
                                (long) (buffer[ib + 3] & 0xff) << 24
                                        | (buffer[ib + 2] & 0xff) << 16
                                        | (buffer[ib + 1] & 0xff) << 8
                                        |  buffer[ib] & 0xff);
                } else if (fi.fileType == FileInfo.GRAY32_INT) {
                    for (int ib = 0; ib < bufferSize; ib += 4, ++iv)
                        pixels[iv % nChannels][iv / nChannels] = (float) (
                                (buffer[ib + 3] & 0xff) << 24
                                        | (buffer[ib + 2] & 0xff) << 16
                                        | (buffer[ib + 1] & 0xff) << 8
                                        |  buffer[ib] & 0xff);
                }
            } else {
                if (fi.fileType == FileInfo.GRAY32_FLOAT) {
                    for (int ib = 0; ib < bufferSize; ib += 4, ++iv)
                        pixels[iv % nChannels][iv / nChannels] = Float.intBitsToFloat(
                                (buffer[ib] & 0xff) << 24
                                        | (buffer[ib + 1] & 0xff) << 16
                                        | (buffer[ib + 2] & 0xff) << 8
                                        | buffer[ib + 3] & 0xff);
                } else if (fi.fileType == FileInfo.GRAY32_UNSIGNED) {
                    // Cast to a long to preserve the most significant bit.
                    for (int ib = 0; ib < bufferSize; ib += 4, ++iv)
                        pixels[iv % nChannels][iv / nChannels] = (float) (
                                (long) (buffer[ib] & 0xff) << 24
                                        | (buffer[ib + 1] & 0xff) << 16
                                        | (buffer[ib + 2] & 0xff) << 8
                                        | buffer[ib + 3] & 0xff);
                } else if (fi.fileType == FileInfo.GRAY32_INT) {
                    for (int ib = 0; ib < bufferSize; ib += 4, ++iv)
                        pixels[iv % nChannels][iv / nChannels] = (float) (
                                (buffer[ib] & 0xff) << 24
                                        | (buffer[ib + 1] & 0xff) << 16
                                        | (buffer[ib + 2] & 0xff) << 8
                                        | buffer[ib + 3] & 0xff);
                }
            }
            showProgress(bytesRead, byteCount);
        }
        return pixels;
    }

    /**
     * Reads a 64-bit image with multiple channels in the axis order CXY.
     * Down-casts the double values to floats which Fiji can handle.
     * @param in    Where to read image bytes from.
     * @return      An array of image pixel data arrays, one for each channel.
     * @throws IOException  When the input stream fails to read.
     */
    float[][] read64bitImageAs32bit(InputStream in) throws IOException {
        byte[] buffer = new byte[bufferSize];
        float[][] pixels = new float[nChannels][nPixels];
        long bytesRead = 0L;

        int iv = 0;  // The value index counts how many numbers have been decoded.
        while (bytesRead < byteCount) {
            bytesRead = readBuffer(in, buffer, bytesRead);

            // Decode the pixels from the buffer.
            if (fi.intelByteOrder) {
                for (int ib = 0; ib < bufferSize; ib += 8, ++iv)
                    pixels[iv % nChannels][iv / nChannels] = (float) Double.longBitsToDouble(
                            (long) (buffer[ib + 7] & 0xff) << 56
                                    | (long) (buffer[ib + 6] & 0xff) << 48
                                    | (long) (buffer[ib + 5] & 0xff) << 40
                                    | (long) (buffer[ib + 4] & 0xff) << 32
                                    | (long) (buffer[ib + 3] & 0xff) << 24
                                    |        (buffer[ib + 2] & 0xff) << 16
                                    |        (buffer[ib + 1] & 0xff) << 8
                                    |         buffer[ib] & 0xff);
            } else {
                for (int ib = 0; ib < bufferSize; ib += 8, ++iv)
                    pixels[iv % nChannels][iv / nChannels] = (float) Double.longBitsToDouble(
                            (long) (buffer[ib] & 0xff) << 56
                                    | (long) (buffer[ib + 1] & 0xff) << 48
                                    | (long) (buffer[ib + 2] & 0xff) << 40
                                    | (long) (buffer[ib + 3] & 0xff) << 32
                                    | (long) (buffer[ib + 4] & 0xff) << 24
                                    |        (buffer[ib + 5] & 0xff) << 16
                                    |        (buffer[ib + 6] & 0xff) << 8
                                    |         buffer[ib + 7] & 0xff);
            }
            showProgress(bytesRead, byteCount);
        }
        return pixels;
    }

    /**
     * The first time an EOF error occurs, we let the user know and continue regardless.
     */
    private void warnEOF() {
        if (mustWarnEOF) {
            IJ.error("EOF Warning", "Reached the end of the file unexpectedly; the image may be truncated.");
            mustWarnEOF = false;
        }
    }

    private void showProgress(int current, int last) {
        if (showProgressBar && System.currentTimeMillis() - startTime > 500L)
            IJ.showProgress(current, last);
    }

    private void showProgress(long current, long last) {
        showProgress((int) (current / 10L), (int) (last / 10L));
    }

    private void chooseBufferSize(long bytesPerPixel) {
        byteCount = bytesPerPixel * fi.nChannels * fi.width * fi.height;
        bufferSize = (int) (byteCount / 25L);
        bufferSize = bufferSize <= 8192 ? 8192 : bufferSize / 8192 * 8192;
    }

    public Object[] readPixels(InputStream in, boolean showProgressBar) throws IOException {
        this.showProgressBar = showProgressBar;
        return readPixels(in);
    }

    public Object[] readPixels(InputStream in) throws IOException {
        startTime = System.currentTimeMillis();

        Object[] pixels = null;
        switch (fi.fileType) {
            case NrrdFileInfo.GRAY8:
            case NrrdFileInfo.COLOR8:
            case NrrdFileInfo.GRAY8_SIGNED:
                chooseBufferSize(1);
                pixels = read8bitImage(in);
                break;
            case NrrdFileInfo.GRAY16_SIGNED:
            case NrrdFileInfo.GRAY16_UNSIGNED:
                chooseBufferSize(2);
                pixels = read16bitImage(in);
                break;
            case NrrdFileInfo.GRAY32_INT:
            case NrrdFileInfo.GRAY32_UNSIGNED:
            case NrrdFileInfo.GRAY32_FLOAT:
                chooseBufferSize(4);
                pixels = read32bitImage(in);
                break;
            case NrrdFileInfo.GRAY64_FLOAT:
                chooseBufferSize(8);
                pixels = read64bitImageAs32bit(in);
                break;
        }
        if (showProgressBar) showProgress(1, 1);
        return pixels;
    }
}