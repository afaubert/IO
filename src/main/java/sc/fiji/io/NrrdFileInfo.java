/*-
 * #%L
 * IO plugin for Fiji.
 * %%
 * Copyright (C) 2008 - 2024 Fiji developers.
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

/* Revised from NrrdReader and NrrdWriter by Andre Faubert on 4/10/2024:
 * Added support for multichannel images of up to 5D strictly following the axis order, CXYZT.
 * All legal NRRD header lines are recorded as fields in the file info object.
 * More strictly follows the NRRD specification.
 * Drastically improved error checking.
 *
 * Aspects of the official specification which were NOT implemented:
 * Multiple data files
 * Arbitrary axis orderings
 * >5 dimensions
 * The "block" data type
 * Hex and ASCII encodings
 * BZip2 compression
 * Recording comments
 * The ability to add literal newline characters to fields as \n, or to interpret \\ as a single \.
 * The ability to add literal double quotes inside a subfield as \".
 *
 * Wishlist:
 * When initializing from a non-NRRD file info, fill the applicable fields from the ImageJ preferences.
 * Use the old min/max fields to set the calibration function.
 */

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.VirtualStack;
import ij.io.FileInfo;
import ij.measure.Calibration;
import ij.process.ImageProcessor;

import java.io.*;
import java.lang.reflect.Array;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;


/**
 * This class acts as a regular FileInfo object while adding support for attributes
 * specific to NRRD files. It also acts to parse the header.
 * @see <a href="https://teem.sourceforge.net/nrrd/format.html">NRRD Specification</a>
 * @author Andre Faubert 2024-04
 * @author Gregory Jefferis 2007
 */
public class NrrdFileInfo extends FileInfo {
    // This is the latest version of the NRRD specification as of 4/14/2024.
    // https://teem.sourceforge.net/nrrd/format.html
    public static final int SUPPORTED_NRRD_VERSION = 5;

    // To identify strings containing a valid digit format. Examples:
    //   "%d" - Valid
    //   "%%d" - Invalid
    //   "hello_%d.png" - Valid
    //   "hocus%7.3d" - Valid
    //   "hocus%i" - Invalid
    //   "%+ 3d" - Valid
    private static final Pattern DIGIT_FORMAT_PAT = Pattern.compile("(^|[^%])%[\\d\\-.+*# ]*d");
    // Matches space-separated spans of text where text containing spaces can be double-quoted to group.
    private static final Pattern SUBFIELD_PAT = Pattern.compile("(\".*?\"|\\S+)(\\s+|$)");
    // Matches space-separated spans surrounded by parentheses, or the word, "none".
    private static final Pattern VECTOR_PAT = Pattern.compile("(none|\\(.+?\\))(\\s+|$)");
    // Separates objects field inside a vector.
    private static final Pattern VECTOR_SPLIT_PAT = Pattern.compile("\\s*,\\s*");

    // Another `fileType` enum value defined for signed 8-bit values. This is not natively supported
    // by ImageJ, so it should be converted to GRAY16_SIGNED after reading the pixel data.
    public static final int GRAY8_SIGNED = 81;
    // COLOR8 is deliberately placed after GRAY8 so that images will prioritize
    // GRAY8 over COLOR8 when read, while still allowing COLOR8 images to be written.
    public static final int[] DATA_TYPES = {GRAY8, COLOR8, GRAY8_SIGNED, GRAY16_UNSIGNED, GRAY16_SIGNED,
            GRAY32_UNSIGNED, GRAY32_INT, GRAY32_FLOAT, GRAY64_FLOAT};
    // Corresponding to `dataTypes`. The first value is the default used when writing.
    public static final String[][] DATA_TYPE_ALIASES = {
            {"uint8", "uint8_t", "uchar", "unsigned char"},
            {"uint8"},
            {"int8", "int8_t", "signed char"},
            {"uint16", "uint16_t", "ushort", "unsigned short", "unsigned short int"},
            {"int16", "int16_t", "short", "short int", "signed short", "signed short int"},
            {"uint32", "uint32_t", "uint", "unsigned int"},
            {"int32", "int32_t", "int", "signed int"},
            {"float"},
            {"double"}
    };
    // Additional values for FileInfo.compression
    public static final int GZIP = 1001;
    public static final int BZIP2 = 1002;
    public static final int HEX = 1003;
    public static final int TEXT = 1004;
    // Additional value for FileInfo.fileFormat
    public static final int NRRD = 1001;
    // New default value for FileInfo.fileType
    public static final int UNDEFINED_TYPE = -1;

    // Additional misc. NRRD info:
    public long headerLength = 0;  // The length of the header in bytes. Used for reading.
    public int headerVersion = 0;  // The version number at the start of the NRRD header file. Used for reading.
    public boolean headless = false;  // Is this a detached header?
    public int nChannels = 1;
    public int nSlices = 1;
    public int nFrames = 1;
    // Key/value pairs for arbitrary user-defined fields.
    // See https://teem.sourceforge.net/nrrd/format.html#general.2
    public HashMap<String, String> customFields = new HashMap<>();

    // NRRD fields which do not align with existing FileInfo fields:
    public long lineSkip = 0;
    public NrrdAxis[] axes = null;
    public double valueMin = Double.NaN;
    public double valueMax = Double.NaN;
    public double valueMinOld = Double.NaN;
    public double valueMaxOld = Double.NaN;
    // Space descriptors:
    public int spaceDim = 0;  // The master which decides whether this file has space fields.
    public String space = null;  // Must agree with spaceDim.
    public String[] spaceUnits = null;  // Is length `spaceDim` when non-null.
    public double[] spaceOrigin = null;  // Is length `spaceDim` when non-null.
    public double[][] spaceDirections = null;  // Is length `spaceDim` when non-null.
    public double[][] measurementFrame = null;  // Is length `spaceDim` when non-null.

    // Indices into the `axes` field. A value of -1 indicates this axis does not exist.
    public int cAxis = -1;
    public int xAxis = -1;
    public int yAxis = -1;
    public int zAxis = -1;
    public int tAxis = -1;

    // Indices into the `space` fields... Unfortunately, these are independent of the axis indices.
    public int xSpaceAxis = -1;
    public int ySpaceAxis = -1;
    public int zSpaceAxis = -1;
    public int tSpaceAxis = -1;

    public NrrdFileInfo() {
        fileFormat = NrrdFileInfo.NRRD;
        height = 1;
        nImages = 1;
        intelByteOrder = true;
        fileType = UNDEFINED_TYPE;
    }

    /**
     * Copy parameters from a regular FileInfo.
     * @param fi    A regular FileInfo, possibly from a TIFF file.
     */
    public NrrdFileInfo(FileInfo fi) {
        this();

        // Fields which are reset instead of copied:
        offset = 0;
        longOffset = 0L;
        gapBetweenImages = 0;
        longGap = 0L;
        if (fi == null) {
            return;
        }

        fileType = fi.fileType;
        fileName = fi.fileName;
        directory = fi.directory;
        url = fi.url;
        width = fi.width;
        height = fi.height;
        nImages = fi.nImages;
        compression = fi.compression;
        intelByteOrder = fi.intelByteOrder;
        inputStream = fi.inputStream;
        pixels = fi.pixels;
        virtualStack = fi.virtualStack;
        sliceNumber = fi.sliceNumber;
        whiteIsZero = fi.whiteIsZero;
        stripOffsets = fi.stripOffsets;
        stripLengths = fi.stripLengths;
        rowsPerStrip = fi.rowsPerStrip;
        lutSize = fi.lutSize;
        reds = fi.reds;
        greens = fi.greens;
        blues = fi.blues;
        sliceLabels = fi.sliceLabels;
        debugInfo = fi.debugInfo;
        description = fi.description;

        // Calibration:
        info = fi.info;
        pixelWidth = fi.pixelWidth;
        pixelHeight = fi.pixelHeight;
        pixelDepth = fi.pixelDepth;
        frameInterval = fi.frameInterval;
        unit = fi.unit;
        valueUnit = fi.valueUnit;
        calibrationFunction = fi.calibrationFunction;
        coefficients = fi.coefficients;

        // Extra metadata that can be stored in a TIFF header:
        metaDataTypes = fi.metaDataTypes;
        metaData = fi.metaData;
        displayRanges = fi.displayRanges;
        channelLuts = fi.channelLuts;
        plot = fi.plot;
        roi = fi.roi;
        overlay = fi.overlay;
        samplesPerPixel = fi.samplesPerPixel;
        openNextDir = fi.openNextDir;
        openNextName = fi.openNextName;
        properties = fi.properties;
        imageSaved = fi.imageSaved;

        // NrrdFileInfo-specific fields.
        headless = fi.fileName.toLowerCase().endsWith(".nhdr");
    }

    public String toString() {
        String x = super.toString();
        x += "; NRRD"
                + ": headerVersion=" + headerVersion
                + ", headless=" + headless
                + ", nChannels=" + nChannels
                + ", nSlices=" + nSlices
                + ", nFrames=" + nFrames
                + ", lineSkip=" + lineSkip
                + ", space=" + space
                + ", spaceDim=" + spaceDim
                + ", spaceUnits=" + Arrays.toString(spaceUnits)
                + ", spaceOrigin=" + Arrays.toString(spaceOrigin)
                + ", spaceDirection=" + Arrays.deepToString(spaceDirections)
                + ", measurementFrame=" + Arrays.deepToString(measurementFrame)
                + ", valueMin=" + valueMin
                + ", valueMax=" + valueMax
                + ", valueMinOld=" + valueMinOld
                + ", valueMaxOld=" + valueMaxOld
                + ", axes=" + Arrays.toString(axes)
                + ", axis indices (CXYZT)=(" + cAxis + ", " + xAxis + ", " + yAxis + ", " + zAxis + ", " + tAxis + ")"
                + ", axis space indices (XYZT)=(" + xSpaceAxis + ", " + ySpaceAxis + ", " + zSpaceAxis
                    + ", " + tSpaceAxis + ")"
                + ", customFields=" + customFields;
        return x;
    }

    /**
     * @return  The number of bytes required to store this image when read.
     */
    public long byteSize() {
        return (long) width * height * nImages * getBytesPerPixel();
    }

    /**
     * Decode the version number from the first line of the file header and check that the magic number matches.
     * @param versionLine   The line containing the NRRD magic number and version information.
     * @throws IOException  When the magic number is wrong.
     * @throws NumberFormatException    When the number isn't formatted as an integer.
     */
    public void parseVersion(String versionLine) throws IOException, NumberFormatException {
        if (versionLine == null || !versionLine.startsWith("NRRD"))
            throw new IOException("Missing the magic number, 'NRRDxxxx'. Is this really an NRRD file?");
        if (!versionLine.substring(8).isEmpty())
            throw new IOException("Invalid magic number, '" + versionLine + "'. Should be exactly four digits.");
        headerVersion = Integer.parseInt(versionLine.substring(4, 8));
    }

    /**
     * Parse an individual line from the header, incorporating the information contained, and checking for errors.
     * @param line  A line from the NRRD header.
     * @throws NrrdException When information contained in the line is inconsistent with expectations or prior lines.
     * @throws NumberFormatException When a number contained in the line is improperly formatted.
     */
    void parseHeaderLine(String line) throws NrrdException, NumberFormatException {
        if (line.startsWith("#")) return;  // Ignore comments.
        String fieldType = parseFieldType(line);
        if (fieldType.isEmpty())
            throw new NrrdException("Missing field. Each line must contain ': ' or ':=' to define a field.");
        String fieldValue = parseFieldValue(line);

        if (fieldIsCustom(line)) {
            if (customFields.containsKey(fieldType))
                throw new NrrdException("Duplicate optional field, '" + fieldType + "'.");
            customFields.put(fieldType, fieldValue);
            return;
        }

        fieldType = fieldType.toLowerCase().replaceAll("\\s+", "");
        fieldValue = fieldValue.trim();
        String fieldValueLC = fieldValue.toLowerCase();
        String[] subfields = parseSubfields(fieldValue);
        if (subfields.length < 1) throw new NrrdException("Missing field value.");
        if (IJ.debugMode) IJ.log("Field '" + fieldType + "' = '" + fieldValue + "'");

        switch (fieldType) {
            case "datafile":
                if (headless) throw new NrrdException("Duplicate 'data file' field encountered.");
                // This is a detached header file.
                headless = true;
                // There are 3 kinds of specification for the data files.
                //  1. data file: <filename>
                //  2. data file: <format> <min> <max> <step> [<subdim>]
                //  3. data file: LIST [<subdim>]
                if ("LIST".equals(subfields[0])) {
                    // 3. data file: LIST [<subdim>]
                    // Example:
                    //   data file: LIST 1
                    //   this is a new line.raw
                    //   this is another line.raw<EOF>
                    throw new NrrdException("Not yet able to handle datafile: LIST specifications.");
                }
                if (4 <= subfields.length && subfields.length <= 5
                        && DIGIT_FORMAT_PAT.matcher(subfields[0]).find()) {
                    // 2. data file: <format> <min> <max> <step> [<subdim>]
                    // Example:
                    //   data file: "I.%03d" 1 200 2 1
                    throw new NrrdException("Not yet able to handle datafile: sprintf file specifications.");
                }
                // 1. data file: <filename>
                // First assume the file is a relative path
                File imageFile = new File(directory, fieldValue);
                if (!imageFile.isFile())  // That didn't work. Maybe it's an absolute path?
                    imageFile = new File(fieldValue);
                if (!imageFile.isFile())
                    throw new NrrdException("Can't find image file at '" + imageFile.getPath() + "'");
                directory = IJ.addSeparator(imageFile.getParent());
                fileName = imageFile.getName();
                return;
            case "lineskip":
                if (lineSkip != 0) throw new NrrdException("Duplicate 'line skip' field encountered.");
                lineSkip = Long.parseLong(fieldValue);
                if (lineSkip < 0) throw new NrrdException("Line skip must not be negative.");
                return;
            case "byteskip":
                if (longOffset != 0) throw new NrrdException("Duplicate 'byte skip' field encountered.");
                longOffset = Long.parseLong(fieldValueLC);
                // -1 is a special value saying to find the image data at the end of the file.
                if (longOffset < -1) throw new NrrdException("'byte skip' must be non-negative or equal -1.");
                return;
            case "type":
                if (fileType != UNDEFINED_TYPE) throw new NrrdException("Duplicate 'type' field encountered.");
                fileType = parseDataType(fieldValueLC);
                return;
            case "endian":
                intelByteOrder = parseEndian(fieldValueLC);
                return;
            case "encoding":
                if (compression != 1) throw new NrrdException("Duplicate 'encoding' field encountered.");
                compression = parseEncoding(fieldValueLC);
                return;
            case "min":
                if (!Double.isNaN(valueMin)) throw new NrrdException("Duplicate 'min' field encountered.");
                valueMin = parseDouble(fieldValue);
                return;
            case "max":
                if (!Double.isNaN(valueMax)) throw new NrrdException("Duplicate 'max' field encountered.");
                valueMax = parseDouble(fieldValue);
                return;
            case "oldmin":
                if (!Double.isNaN(valueMinOld)) throw new NrrdException("Duplicate 'old min' field encountered.");
                valueMinOld = parseDouble(fieldValue);
                return;
            case "oldmax":
                if (!Double.isNaN(valueMaxOld)) throw new NrrdException("Duplicate 'old max' field encountered.");
                valueMaxOld = parseDouble(fieldValue);
                return;
            case "sampleunits":
                if (valueUnit != null) throw new NrrdException("Duplicate 'sample units' field encountered.");
                valueUnit = fieldValue;
                if (stringIsNone(valueUnit)) valueUnit = null;
                return;
            case "content":
                if (info != null) throw new NrrdException("Duplicate 'content' field encountered.");
                // `info` seems like the closest ImageJ equivalent. There is also `description` and
                // an unlimited number of `properties` fields.
                info = fieldValue;
                if (stringIsNone(info)) info = null;
                return;
            case "dimension":
                if (axes != null) throw new NrrdException("Duplicate 'dimension' field encountered.");
                int n = Integer.parseInt(fieldValue);
                if (n < 1) throw new NrrdException("'dimension' must be positive.");
                if (n > 5) throw new NrrdException("'dimension'>5 is not supported.");
                axes = new NrrdAxis[n];
                for (int i = 0; i < n; ++i)
                    axes[i] = new NrrdAxis();
                return;
            case "space":
                // The space is a coordinate system entirely independent of the image array axes.
                // See https://teem.sourceforge.net/nrrd/format.html#space
                if (axes == null) throw new NrrdException("Must specify 'dimension' before 'space'");
                if (space != null) throw new NrrdException("Duplicate 'space' field encountered.");
                if (spaceDim != 0) throw new NrrdException("Only one 'space' or 'space dimension' field is permitted.");
                space = fieldValue;
                spaceDim = parseSpaceDim(fieldValue);
                if (spaceDim > axes.length)
                    throw new NrrdException("'space dimension' is incompatible with 'dimension' = " + axes.length + ".");
                return;
            case "spacedimension":
                if (axes == null) throw new NrrdException("Must specify 'dimension' before 'space dimension'");
                if (space != null) throw new NrrdException("Only one 'space' or 'space dimension' field is permitted.");
                if (spaceDim != 0) throw new NrrdException("Duplicate 'space dimension' field encountered.");
                spaceDim = Integer.parseInt(fieldValue);
                if (spaceDim < 1) throw new NrrdException("'space dimension' field must be positive.");
                if (spaceDim > 4) throw new NrrdException("'space dimension' > 4 is not supported.");
                if (spaceDim > axes.length)
                    throw new NrrdException("'space dimension' is incompatible with 'dimension' = " + axes.length + ".");
                return;
            // Proceed to handle per-space-dimension types.
            case "spaceunits":
            case "spaceorigin":
            case "spacedirections":
            case "measurementframe":
                if (spaceDim == 0)
                    throw new NrrdException("'space dimension' must be specified before per-dimension field, '"
                        + fieldType + "'.");
            // Proceed to handle per-axis types.
            case "sizes":
            case "spacings":
            case "thicknesses":
            case "centers":
            case "centerings":
            case "axismins":
            case "axismaxs":
            case "units":
            case "kinds":
            case "labels":
                break;
            case "blocksize":  // ImageJ can't handle block data types.
            case "number":  // This field type is deprecated.
                return;
            default:
                throw new NrrdException("Unrecognized standard field: '" + fieldType
                        + "'. Use := to define custom fields.");
        }

        // Handle per-space-dimension types.
        switch (fieldType) {
            case "spaceunits":
                if (spaceUnits != null) throw new NrrdException("Duplicate 'space units' field encountered.");
                if (subfields.length != spaceDim)
                    throw new NrrdException("'space units' must must specify a value for each of the "
                            + spaceDim + " spatial dimensions.");
                spaceUnits = subfields;
                return;
            case "spaceorigin":
                if (spaceOrigin != null) throw new NrrdException("Duplicate 'space origin' field encountered.");
                double[][] vectors = parseVectors(fieldValue);
                if (vectors.length != 1) throw new NrrdException("'space origin' must contain a single vector.");
                if (spaceDim != vectors[0].length)
                    throw new NrrdException("'space origin' must be of dimensionality, 'space dimension' = "
                            + spaceDim + ".");
                spaceOrigin = vectors[0];
                return;
            case "spacedirections":
                // The specification provides self-contradictory examples, saying that this is both a per-axis and
                // a per-space-axis field. I have chosen to make it per-space-axis to be compatible with the PyNRRD
                // library, and to maintain consistency with the other "space" fields.
                if (spaceDirections != null) throw new NrrdException("Duplicate 'space directions' field encountered.");
                spaceDirections = parseVectors(fieldValue);
                if (spaceDirections.length != spaceDim)
                    throw new NrrdException("'space directions'' must be a DxD matrix where D = 'space dimension' = "
                            + spaceDim + ".");
                // Not all space directions need be specified.
                for (int i = 0; i < spaceDim; ++i) {
                    double[] d = spaceDirections[i];
                    if (d == null) continue;
                    if (d.length != spaceDim) throw new NrrdException("'space directions' row " + (i + 1)
                            + " != 'space dimension' = " + spaceDim + ".");
                    for (int j = 0; j < spaceDim; ++j)
                        if (Double.isInfinite(d[j]))
                            throw new NrrdException("'space directions' must contain finite values.");
                }
                return;
            case "measurementframe":
                if (measurementFrame != null) throw new NrrdException("Duplicate 'measurement frame' field encountered.");
                measurementFrame = parseVectors(fieldValue);
                if (measurementFrame.length != spaceDim)
                    throw new NrrdException("'measurement frame' must be a DxD matrix where D = 'space dimension' = "
                            + spaceDim + ".");
                for (int i = 0; i < spaceDim; ++i)
                    if (measurementFrame[i] == null || measurementFrame[i].length != spaceDim)
                        throw new NrrdException("'measurement frame' row " + (i+1) + " != 'space dimension' = "
                                + spaceDim + ".");
                return;
        }

        // Handle per-axis types.
        if (axes == null)
            throw new NrrdException("'dimension' must be specified before per-axis field, '" + fieldType + "'.");
        if (subfields.length != axes.length)
            throw new NrrdException("'" + fieldType + "' must specify a value for each of the "
                    + axes.length + " axes.");
        switch (fieldType) {
            case "sizes":
                for (int i = 0; i < axes.length; i++) {
                    if (axes[i].size != 0) throw new NrrdException("Duplicate 'sizes' field encountered.");
                    axes[i].size = Integer.parseInt(subfields[i]);
                    if (axes[i].size <= 0) throw new NrrdException("'sizes' must contain positive values.");
                }
                return;
            case "spacings":
                for (int i = 0; i < axes.length; i++) {
                    if (!Double.isNaN(axes[i].spacing))
                        throw new NrrdException("Duplicate 'spacings' field encountered.");
                    axes[i].spacing = parseDouble(subfields[i]);
                    // For some reason, spacings are allowed to be negative.
                    if (axes[i].spacing == 0) throw new NrrdException("'spacings' must contain non-zero values.");
                    if (Double.isInfinite(axes[i].spacing))
                        throw new NrrdException("'spacings' must contain finite values.");
                }
                return;
            case "thicknesses":
                for (int i = 0; i < axes.length; i++) {
                    if (!Double.isNaN(axes[i].thickness))
                        throw new NrrdException("Duplicate 'thicknesses' field encountered.");
                    axes[i].thickness = parseDouble(subfields[i]);
                    if (axes[i].thickness <= 0) throw new NrrdException("'thicknesses' must contain positive values.");
                    if (Double.isInfinite(axes[i].thickness))
                        throw new NrrdException("'thicknesses' must contain finite values.");
                }
                return;
            case "centers":
            case "centerings":
                for (int i = 0; i < axes.length; i++) {
                    if (axes[i].center != NrrdAxis.CENTER_UNKNOWN)
                        throw new NrrdException("Duplicate 'centers' field encountered.");
                    axes[i].center = parseCentering(subfields[i]);
                }
                return;
            case "axismins":
                for (int i = 0; i < axes.length; i++) {
                    if (!Double.isNaN(axes[i].min))
                        throw new NrrdException("Duplicate 'axis mins' field encountered.");
                    axes[i].min = parseDouble(subfields[i]);
                    if (Double.isInfinite(axes[i].min))
                        throw new NrrdException("'axis mins' must contain finite values.");
                }
                return;
            case "axismaxs":
                for (int i = 0; i < axes.length; i++) {
                    if (!Double.isNaN(axes[i].max))
                        throw new NrrdException("Duplicate 'axis maxs' field encountered.");
                    axes[i].max = parseDouble(subfields[i]);
                    if (Double.isInfinite(axes[i].max))
                        throw new NrrdException("'axis maxs' must contain finite values.");
                }
                return;
            case "units":
                for (int i = 0; i < axes.length; i++) {
                    if (axes[i].unit != null)
                        throw new NrrdException("Duplicate 'units' field encountered.");
                    axes[i].unit = subfields[i];
                }
                return;
            case "kinds":
                for (int i = 0; i < axes.length; i++) {
                    if (axes[i].kind != null)
                        throw new NrrdException("Duplicate 'kinds' field encountered.");
                    axes[i].kind = subfields[i];
                    int size = kindAxisSize(axes[i].kind);
                    if (size != 0 && size != axes[i].size)
                        throw new NrrdException("Kind, '" + axes[i].kind + "' must correspond to size " + size + ".");
                }
                return;
            case "labels":
                for (int i = 0; i < axes.length; i++) {
                    if (axes[i].label != null)
                        throw new NrrdException("Duplicate 'labels' field encountered.");
                    axes[i].label = subfields[i];
                }
        }
    }

    /**
     * Parse lines from the header exclusively containing custom fields, overwriting the existing fields.
     * @param customFieldsBlock A region of text containing zero or more lines of potential fields.
     * @throws NrrdException    When
     */
    void parseCustomFields(String customFieldsBlock) throws NrrdException {
        customFields = new HashMap<>();
        String[] lines = customFieldsBlock.split("\n");
        for (int i = 0; i < lines.length; ++i) {
            String line = lines[i];
            if (line.startsWith("#")) continue;   // Ignore comments.
            if (line.trim().isEmpty()) continue;  // Ignore blank lines.
            if (!fieldIsCustom(line))
                throw new NrrdException("Optional fields must be defined using ':='.", i + 1);
            String fieldType = parseFieldType(line);
            if (fieldType.isEmpty())
                throw new NrrdException("Optional fields must define a field key.", i + 1);
            String fieldValue = parseFieldValue(line);
            if (customFields.containsKey(fieldType))
                throw new NrrdException("Duplicate optional field key, '" + fieldType + "'.", i + 1);
            customFields.put(fieldType, fieldValue);
        }
    }

    /**
     * Update the information to reflect that contained in the ImagePlus in preparation for file writing.
     * This is equivalent to getFileInfo, except it also prepares the NRRD-specific fields.
     * @param imp               The ImagePlus associated.
     * @throws NrrdException    When the datatype is unsupported.
     * @see ij.ImagePlus#getFileInfo()
     */
    void fromImagePlus(ImagePlus imp) throws NrrdException {
        offset = 0;
        longOffset = 0;
        lineSkip = 0;
        width = imp.getWidth();
        height = imp.getHeight();
        nChannels = imp.getNChannels();
        nSlices = imp.getNSlices();
        nFrames = imp.getNFrames();
        nImages = imp.getImageStackSize();
        if (nImages == 1) {
            ImageProcessor ip = imp.getProcessor();
            if (ip != null) pixels = ip.getPixels();
        } else {
            ImageStack stack = imp.getStack();
            pixels = stack.getImageArray();
            // Pixels will be null when this is a virtual stack.
            if (stack.isVirtual()) virtualStack = (VirtualStack) stack;
        }
        {   // Indices into the `axes` field.
            int na = 0;  // Number of axes.
            int[] oldAxisIndices = {cAxis, xAxis, yAxis, zAxis, tAxis};

            cAxis = nChannels > 1 ? na++ : -1;
            xAxis = na++;
            yAxis = height > 1 ? na++ : -1;
            zAxis = nSlices > 1 ? na++ : -1;
            tAxis = nFrames > 1 ? na++ : -1;
            int[] newAxisIndices = {cAxis, xAxis, yAxis, zAxis, tAxis};
            NrrdAxis[] oldAxes = axes;  // Should not be null when any old axis indices is non-negative.
            axes = new NrrdAxis[na];
            for (int i = 0; i < 5; ++i) {
                int ni = newAxisIndices[i];
                int oi = oldAxisIndices[i];
                if (ni > -1) axes[ni] = oi > -1 ? oldAxes[oi] : new NrrdAxis(i);
            }
        }
        // Count how many space axes should exist.
        spaceDim = 1;
        for (int i : new int[] {yAxis, zAxis, tAxis})
            if (i > -1) ++spaceDim;
        try {
            if (space != null && spaceDim != parseSpaceDim(space)) space = null;
        } catch (NrrdException e) {
            space = null;
        }
        {
            int[] oldSpaceIndices = {xSpaceAxis, ySpaceAxis, zSpaceAxis, tSpaceAxis};
            solveSpaceIndices();
            int[] newSpaceIndices = {xSpaceAxis, ySpaceAxis, zSpaceAxis, tSpaceAxis};
            boolean spacesChanged = false;
            for (int i = 0; i < 4; ++i) spacesChanged |= oldSpaceIndices[i] != newSpaceIndices[i];
            if (spacesChanged) {
                // We need to preserve the time origin, if possible, since it can't be stored in Calibration.
                // Regular origin info will be overwritten regardless.
                double[] oldSpaceOrigin = spaceOrigin;
                newSpaceOrigin();
                if (oldSpaceOrigin != null && tSpaceAxis > -1 && oldSpaceIndices[3] > -1)
                    spaceOrigin[tSpaceAxis] = oldSpaceOrigin[oldSpaceIndices[3]];

                // It's too challenging to adjust the measurement frame in a sensible way.
                measurementFrame = null;

                // Space directions and units are always overwritten from the calibration regardless.
            }
        }

        // Spacings, min/max/centers, and units are not preferred.
        // The only exception is for channel values, which can't otherwise be specified.
        // As such, these field values will be left alone.

        if (cAxis > -1) axes[cAxis].size = nChannels;
        if (xAxis > -1) {
            axes[xAxis].size = width;
            axes[xAxis].kind = "space";
        }
        if (yAxis > -1) {
            axes[yAxis].size = height;
            axes[yAxis].kind = "space";
        }
        if (zAxis > -1) {
            axes[zAxis].size = nSlices;
            axes[zAxis].kind = "space";
        }
        if (tAxis > -1) {
            axes[tAxis].size = nFrames;
            axes[tAxis].kind = "time";
        }

        // Copy calibration.
        Calibration cal = imp.getCalibration();
        if (cal.info != null) info = cal.info;
        calibrationFunction = cal.getFunction();
        coefficients = cal.getCoefficients();
        String calValueUnit = cal.getValueUnit();
        valueUnit = Calibration.DEFAULT_VALUE_UNIT.equals(calValueUnit) ? null : calValueUnit;

        newSpaceUnits();
        newSpaceDirections(true);
        boolean foundNoSpacings = true;
        frameInterval = cal.frameInterval;
        if (frameInterval != 1.0) {
            if (IJ.debugMode) IJ.log("Calibration has a frame interval.");
            if (tSpaceAxis > -1) spaceDirections[tSpaceAxis][tSpaceAxis] = frameInterval;
            if (tSpaceAxis > -1) spaceUnits[tSpaceAxis] = cal.getTimeUnit();
            foundNoSpacings = false;
        }
        // `scaled` checks `cal.unit`, `scaledOrOffset` doesn't.
        if (cal.scaled()) {
            if (IJ.debugMode) IJ.log("Calibration is scaled.");
            unit = cal.getUnit();
            pixelWidth = cal.pixelWidth;
            pixelHeight = cal.pixelHeight;
            pixelDepth = cal.pixelDepth;
            if (xSpaceAxis > -1) spaceUnits[xSpaceAxis] = cal.getXUnit();
            if (ySpaceAxis > -1) spaceUnits[ySpaceAxis] = cal.getYUnit();
            if (zSpaceAxis > -1) spaceUnits[zSpaceAxis] = cal.getZUnit();
            if (xSpaceAxis > -1) spaceDirections[xSpaceAxis][xSpaceAxis] = pixelWidth;
            if (ySpaceAxis > -1) spaceDirections[ySpaceAxis][ySpaceAxis] = pixelHeight;
            if (zSpaceAxis > -1) spaceDirections[zSpaceAxis][zSpaceAxis] = pixelDepth;
            foundNoSpacings = false;
        } else {
            unit = null;
            pixelWidth = pixelHeight = pixelDepth = 1.0;
        }
        if (foundNoSpacings) {
            if (IJ.debugMode) IJ.log("No calibration spacing information exists.");
            spaceUnits = null;
            spaceDirections = null;
        }

        boolean calHasOrigin = cal.xOrigin != 0 || cal.yOrigin != 0 || cal.zOrigin != 0;
        if (calHasOrigin) {
            if (xSpaceAxis > -1) spaceOrigin[xSpaceAxis] = axisOriginNrrd(cal.xOrigin, pixelWidth);
            if (ySpaceAxis > -1) spaceOrigin[ySpaceAxis] = axisOriginNrrd(cal.yOrigin, pixelHeight);
            if (zSpaceAxis > -1) spaceOrigin[zSpaceAxis] = axisOriginNrrd(cal.zOrigin, pixelDepth);
        } else if (tSpaceAxis < 0 || Double.isNaN(spaceOrigin[tSpaceAxis]))
            spaceOrigin = null;

        switch (imp.getType()) {
            case ImagePlus.GRAY8:
                fileType = GRAY8;
                break;
            case ImagePlus.COLOR_256:
                fileType = COLOR8;
                break;
            case ImagePlus.GRAY16:
                fileType = GRAY16_UNSIGNED;
                break;
            case ImagePlus.GRAY32:
                fileType = GRAY32_FLOAT;
                break;
            case ImagePlus.COLOR_RGB:
                throw new NrrdException("RGB data is not supported.");
        }

        valueMin = imp.getDisplayRangeMin();
        valueMax = imp.getDisplayRangeMax();
    }

    /**
     * Parse the NRRD fields into ImageJ calibration settings, both for FileInfo and for Calibration.
     * @param imp   The ImagePlus whose calibration will be adjusted.
     * @return      The calibration from the ImagePlus.
     */
    public Calibration updateCalibration(ImagePlus imp) {
        Calibration cal = imp.getLocalCalibration();
        cal.info = info;

        // Signed shorts are internally represented as unsigned shorts, so calibration is needed
        // to report the correct value.
        if (fileType == GRAY16_SIGNED) {
            if (IJ.debugMode) IJ.log("16-bit signed");
            cal.setSigned16BitCalibration();
        } else if (fileType == GRAY8_SIGNED) {
            fileType = GRAY8;  // GRAY8_SIGNED is not an official ImageJ type, so we relabel it for compatibility.
            double[] coefficients = new double[] {-128.0, 1.0};
            cal.setFunction(Calibration.STRAIGHT_LINE, coefficients, Calibration.DEFAULT_VALUE_UNIT);
        }

        // Display range.
        if (!Double.isNaN(valueMin) && !Double.isNaN(valueMax))
            IJ.setMinAndMax(imp, valueMin, valueMax);

        // Units.
        if (spaceUnits != null) {
            // This might assign the units to null, that's OK.
            if (xAxis > -1) cal.setXUnit(spaceUnits[xSpaceAxis]);
            if (yAxis > -1) cal.setYUnit(spaceUnits[ySpaceAxis]);
            if (zAxis > -1) cal.setZUnit(spaceUnits[zSpaceAxis]);
        } else {
            if (xAxis > -1 && axes[xAxis].unit != null) cal.setXUnit(axes[xAxis].unit);
            if (yAxis > -1 && axes[yAxis].unit != null) cal.setYUnit(axes[yAxis].unit);
            if (zAxis > -1 && axes[zAxis].unit != null) cal.setZUnit(axes[zAxis].unit);
        }
        if (tAxis > -1 && axes[tAxis].unit != null) cal.setTimeUnit(axes[tAxis].unit);
        if (valueUnit != null) cal.setValueUnit(valueUnit);

        // Spacings.
        cal.pixelWidth    = pixelWidth    = axisSpacing(xAxis, xSpaceAxis);
        cal.pixelHeight   = pixelHeight   = axisSpacing(yAxis, ySpaceAxis);
        cal.pixelDepth    = pixelDepth    = axisSpacing(zAxis, zSpaceAxis);
        cal.frameInterval = frameInterval = axisSpacing(tAxis, tSpaceAxis);

        // Origins.
        cal.xOrigin = axisOriginIJ(xAxis, xSpaceAxis, pixelWidth);
        cal.yOrigin = axisOriginIJ(yAxis, ySpaceAxis, pixelHeight);
        cal.zOrigin = axisOriginIJ(zAxis, zSpaceAxis, pixelDepth);

        return cal;
    }

    /* Cell centering (1D example):
       -----------------
       | . | . | . | . |
       -----------------
       Vertices are situated in the center of cells.

       Node centering (1D example):
       ----------
       i  i  i  i
       ----------
       Vertices make up the bounds of the grid cells.
     */

    /**
     * Determine the sample spacing for a given axis index.
     * This spacing is the cell width in physical units, which is independent of centering.
     * Preferred order of derivation: 1. space directions, 2. axis spacing, 3. axis min/max.
     * @param arrayAxis  The index of the actual axis to evaluate. This must be true: arrayAxis < axes.length.
     * @param spaceAxis  The x,y,z,t spatial index to evaluate. This must be true: 0 <= spaceAxis.
     * @return      The sample spacing in whatever units this axis involves, or 1.0 if indeterminable.
     * @see <a href="https://teem.sourceforge.net/nrrd/format.html#centers">NRRD Centers Specification</a>
     */
    private double axisSpacing(int arrayAxis, int spaceAxis) {
        if (arrayAxis < 0) return 1.0;  // Axis is not defined.
        if (-1 < spaceAxis && spaceAxis < spaceDim && spaceDirections != null && spaceDirections[spaceAxis] != null) {
            double sumSq = 0;
            for (int i = 0; i < spaceDim; ++i) {
                double x = spaceDirections[spaceAxis][i];
                if (!Double.isNaN(x)) sumSq += x * x;
            }
            if (sumSq <= 0.0) return 1.0;
            return Math.sqrt(sumSq);
        }
        NrrdAxis a = axes[arrayAxis];
        if (!Double.isNaN(a.spacing)) return a.spacing;
        if (!Double.isNaN(a.min) && !Double.isNaN(a.max)) {
            // `axis min` is the physical position of the first vertex.
            // In node centering, the cell width is range/(n-1), while
            // in cell centering, the cell width is simply range/n.
            // Node centering is taken as the default if `center` is undetermined.
            int centerAdjust = (a.center != NrrdAxis.CENTER_CELL && a.size > 1) ? 1 : 0;
            return (a.max - a.min) / (a.size - centerAdjust);
        }
        return 1.0;
    }

    /**
     * Determine the ImageJ origin for a given axis index.
     * The ImageJ origin is the position in pixels of the origin w.r.t. the first pixel's
     * upper-left corner (node centering), while the NRRD origin is the position in physical units of
     * the first pixel w.r.t. the real origin, either centered or node-based, depending on `centers`.
     * If centers is unknown, default to node centering.
     * Preferred order of derivation: 1. space origin, 2. axis min/max.
     * @param arrayAxis The index of the actual axis to evaluate. This must be true: arrayAxis < axes.length.
     * @param spaceAxis The x,y,z,t spatial index to evaluate. This must be true: 0 <= spaceAxis.
     * @param spacing   The spacing between adjacent samples.
     * @return          The ImageJ origin in pixels relative to the upper-left corner, or 0.0 if indeterminable.
     * @see <a href="https://teem.sourceforge.net/nrrd/format.html#centers">NRRD Centers Specification</a>
     * @see Calibration
     */
    private double axisOriginIJ(int arrayAxis, int spaceAxis, double spacing) {
        if (arrayAxis < 0) return 0.0;  // Axis is not defined.
        // ImageJ uses pixels instead of real units, so conversion must be possible or the origin is meaningless.
        if (spacing <= 0.0) return 0.0;
        NrrdAxis a = axes[arrayAxis];

        // Subtract this to adjust from pointing from the origin to the cell center
        // to pointing from the origin to the cell node.
        // Then negate this vector since ImageJ has the origin vectors backwards.
        // Node centering is taken as the default if `center` is undetermined.
        double centerAdjust = (a.center == NrrdAxis.CENTER_CELL) ? 0.5 : 0;

        // Prefer to use `space origin` over `axis min`. This ALWAYS points to the center regardless of `centers`.
        if (spaceOrigin != null && -1 < spaceAxis && spaceAxis < spaceDim)
            return -(spaceOrigin[spaceAxis] / spacing  - 0.5);
        // `axis min` is the physical position of the first vertex.
        if (!Double.isNaN(a.min)) return -(a.min / spacing - centerAdjust);
        return 0.0;
    }

    /**
     * Reverse the operation from `axisOriginIJ`, yielding the value for an axis of NRRD's "space origin" field.
     * @param originIJ  The coordinate from an ImageJ Calibration object.
     * @param spacing   The distance between adjacent samples along the corresponding axis.
     * @return          The value of a single axis for NRRD's "space origin" field.
     * @see     NrrdFileInfo#axisOriginIJ
     * @see     Calibration
     */
    private double axisOriginNrrd(double originIJ, double spacing) {
        return spacing * (0.5 - originIJ);
    }

    /**
     * Create a header string from the information contained in this object.
     * Make sure to call `fromImagePlus` before making the header.
     * @return  The full header to write to the file.
     * @throws NrrdException When the data cannot be written.
     * @see <a href="http://teem.sourceforge.net/nrrd/format.html">The NRRD Specification</a>
     */
    String asHeader() throws NrrdException {
        StringBuilder out = new StringBuilder();

        out.append("NRRD000").append(SUPPORTED_NRRD_VERSION).append('\n');
        out.append("# Created by Fiji NRRD Writer at ").append(new Date()).append('\n');

        String typeValue = dataName(fileType);
        if (typeValue == null) throw new NrrdException("The supported types are 8-, 16-, 32-, and 64-bit images.");
        out.append("type: ").append(typeValue).append('\n');
        if (!DATA_TYPE_ALIASES[0][0].equals(typeValue))
            out.append("endian: ").append(intelByteOrder ? "little\n" : "big\n");
        out.append("encoding: ").append(encodingName(compression)).append('\n');

        if (axes == null)
            throw new NrrdException("Tried to create a header for an improperly initialized NrrdFileInfo.");
        NrrdAxis[] axesMasked = axesSliceMasked();
        out.append("dimension: ").append(axesMasked.length).append('\n');
        out.append("sizes:");
        for (NrrdAxis axis : axes)
            if (!axis.slice) out.append(' ').append(axis.size);
        out.append('\n');

        boolean anyKinds = false;
        for (NrrdAxis axis : axesMasked)
            anyKinds |= axis.kind != null;
        if (anyKinds) {
            out.append("kinds:");
            for (NrrdAxis axis : axesMasked)
                out.append(' ').append(stringSubfield(axis.kind));
            out.append('\n');
        }

        boolean anyLabels = false;
        for (NrrdAxis axis : axesMasked)
            anyLabels |= axis.label != null;
        if (anyLabels) {
            out.append("labels:");
            for (NrrdAxis axis : axesMasked)
                out.append(' ').append(stringSubfield(axis.label));
            out.append('\n');
        }

        boolean anyUnits = false;
        for (NrrdAxis axis : axesMasked)
            anyUnits |= axis.unit != null;
        if (anyUnits) {
            out.append("units:");
            for (NrrdAxis axis : axesMasked)
                out.append(' ').append(stringSubfield(axis.unit));
            out.append('\n');
        }

        boolean anySpacings = false;
        for (NrrdAxis axis : axesMasked)
            anySpacings |= !Double.isNaN(axis.spacing);
        if (anySpacings) {
            out.append("spacings:");
            for (NrrdAxis axis : axesMasked)
                out.append(' ').append(doubleSubfield(axis.spacing));
            out.append('\n');
        }

        boolean anyThicknesses = false;
        for (NrrdAxis axis : axesMasked)
            anyThicknesses |= !Double.isNaN(axis.thickness);
        if (anyThicknesses) {
            out.append("thicknesses:");
            for (NrrdAxis axis : axesMasked)
                out.append(' ').append(doubleSubfield(axis.thickness));
            out.append('\n');
        }

        boolean anyCenters = false;
        for (NrrdAxis axis : axesMasked)
            anyCenters |= axis.center != NrrdAxis.CENTER_UNKNOWN;
        if (anyCenters) {
            out.append("centers:");
            for (NrrdAxis axis : axesMasked)
                out.append(' ').append(axis.centerText());
            out.append('\n');
        }

        boolean anyAxisMins = false;
        for (NrrdAxis axis : axesMasked)
            anyAxisMins |= !Double.isNaN(axis.min);
        if (anyAxisMins) {
            out.append("axis mins:");
            for (NrrdAxis axis : axesMasked)
                out.append(' ').append(doubleSubfield(axis.min));
            out.append('\n');
        }

        boolean anyAxisMaxs = false;
        for (NrrdAxis axis : axesMasked)
            anyAxisMaxs |= !Double.isNaN(axis.max);
        if (anyAxisMaxs) {
            out.append("axis maxs:");
            for (NrrdAxis axis : axesMasked)
                out.append(' ').append(doubleSubfield(axis.max));
            out.append('\n');
        }

        if (spaceDim > 0) {
            int[] si = spaceAxesSliceMaskedIndices();

            // When slicing the time axis, it would be nice to automatically drop the space from, e.g.,
            // RAST to just RAS, but this seems too niche and tricky to be worth implementing.
            boolean spaceIsValid = space != null && si.length == spaceDim;
            if (spaceIsValid) {
                try {
                    spaceIsValid = parseSpaceDim(space) == spaceDim;
                } catch (NrrdException e) {
                    spaceIsValid = false;
                }
            }
            if (spaceIsValid) out.append("space: ").append(space).append('\n');
            else out.append("space dimension: ").append(si.length).append('\n');

            String[] spaceUnitsM = sampleArray(spaceUnits, si);
            if (spaceUnitsM != null && anyExists(spaceUnitsM)) {
                out.append("space units:");
                for (String s : spaceUnitsM)
                    out.append(' ').append(stringSubfield(s));
                out.append('\n');
            }
            double[] spaceOriginM = sampleNums(spaceOrigin, si);
            if (spaceOriginM != null && anyNumExists(spaceOriginM))
                out.append("space origin: ").append(vectorSubfield(spaceOriginM)).append('\n');
            double[][] spaceDirectionsM = sampleArray(spaceDirections, si);
            if (spaceDirectionsM != null && anyExists(spaceDirectionsM)) {
                // Bug: [[nan, nan], [nan, nan]] will write the field as: none none

                out.append("space directions:");
                for (double[] vec : spaceDirectionsM)
                    out.append(' ').append(vectorSubfield(sampleNums(vec, si)));
                out.append('\n');
            }
            double[][] measurementFrameM = sampleArray(measurementFrame, si);
            if (measurementFrameM != null && anyExists(measurementFrameM)) {
                out.append("measurement frame:");
                for (double[] vec : measurementFrameM)
                    out.append(' ').append(vectorSubfield(sampleNums(vec, si)));
                out.append('\n');
            }
        }

        if (!Double.isNaN(valueMin)) out.append("min: ").append(doubleSubfield(valueMin)).append('\n');
        if (!Double.isNaN(valueMax)) out.append("max: ").append(doubleSubfield(valueMax)).append('\n');
        if (!Double.isNaN(valueMinOld)) out.append("old min: ").append(doubleSubfield(valueMinOld)).append('\n');
        if (!Double.isNaN(valueMaxOld)) out.append("old max: ").append(doubleSubfield(valueMaxOld)).append('\n');
        if (valueUnit != null) out.append("sample units: ").append(cleanStringField(valueUnit)).append('\n');
        if (info != null) out.append("content: ").append(cleanStringField(info)).append('\n');

        out.append(customFieldsBlock());
        // When slicing, custom fields specify the index of this slice.
        // This is the inverse of the slice-masked axes array.
        for (NrrdAxis axis : axes) {
            if (!axis.slice) continue;
            String name = axis.name();
            String keyI = name + " index";
            String keyN = "n " + name + "s";
            if (customFields.containsKey(keyI) || customFields.containsKey(keyN))
                continue;  // Silently fail to write. Should this be an error?
            out.append(keyI).append(":=").append(axis.sliceIndex).append('\n');
            out.append(keyN).append(":=").append(axis.size).append('\n');
        }

        // These are usually not used, but they are added here for the sake of flexibility.
        if (longOffset != 0) out.append("byte skip: ").append(longOffset).append('\n');
        if (lineSkip != 0) out.append("line skip: ").append(lineSkip).append('\n');

        // Always save to relative file paths.
        if (headless) out.append("data file: ").append(dataFileName()).append('\n');

        // A newline terminates the header.
        out.append('\n');
        return out.toString();
    }

    /**
     * @return The file name corresponding to the data part of a headless file pair.
     * Guaranteed to be distinct from the header name.
     */
    public String dataFileName() {
        int i = fileName.lastIndexOf('.');
        String name = i < 0 ? fileName : fileName.substring(0, i);
        name = cleanStringField(name);
        name += ".raw";
        if (compression == GZIP) name += ".gz";
        if (name.equals(fileName)) name += ".dat";
        return name;
    }

    public String formatSliceFileName() {
        int i = fileName.lastIndexOf('.');
        String name, extension;
        if (i < 0) {
            name = fileName;
            extension = headless ? ".nhdr" : ".nrrd";
        } else {
            name = fileName.substring(0, i);
            extension = fileName.substring(i);
        }
        StringBuilder out = new StringBuilder(name);
        for (NrrdAxis axis : axes)
            if (axis.slice)
                out.append(String.format("_%s%0" + axis.digitsForSize() + "d", axis.letter(), axis.sliceIndex));
        out.append(extension);
        return out.toString();
    }

    /**
     * Use when creating a header for a "slice" of an NRRD file.
     * @return The subset of `axes` where `slice` is false, maintaining axis order.
     * @see NrrdFileInfo#asHeader
     */
    private NrrdAxis[] axesSliceMasked() {
        // Over-allocate the result array.
        NrrdAxis[] rx = new NrrdAxis[axes.length];
        int j = 0;
        for (NrrdAxis a : axes)
            if (!a.slice) rx[j++] = a;
        // Trim the result array.
        NrrdAxis[] r = new NrrdAxis[j];
        System.arraycopy(rx, 0, r, 0, j);
        return r;
    }

    /**
     * Use when creating a header for a "slice" of an NRRD file.
     * Example:
     * axes: {c(slice=false), x(slice=false), y(slice=true), z(slice=false)}
     * iAxes: {1, 2, 3, -1}
     * iSpaceAxes: {0, 1, 2, -1}
     * Result: [0, 2]
     * Thus, the indices corresponding to the X and Z spatial axes are returned.
     * @return Indices for space axes where slice=false for the corresponding axis.
     * @see NrrdFileInfo#asHeader
     * @see NrrdFileInfo#sampleArray
     * @see NrrdFileInfo#sampleNums
     */
    private int[] spaceAxesSliceMaskedIndices() {
        final int[] iAxes = new int[] {xAxis, yAxis, zAxis, tAxis};
        final int[] iSpaceAxes = new int[] {xSpaceAxis, ySpaceAxis, zSpaceAxis, tSpaceAxis};
        int[] rx = new int[4];
        int j = 0;
        for (int i = 0; i < 4; ++i) {
            int ia = iAxes[i];
            if (ia > -1 && !axes[ia].slice)
                rx[j++] = iSpaceAxes[i];
        }
        int[] r = new int[j];
        System.arraycopy(rx, 0, r, 0, j);
        return r;
    }

    /**
     * Sample `arr` using `indices`.
     * Example: sampleArray({"a", "b", "c"}, {1, 0, 2, 2}) -> {"b", "a", "c", "c"}.
     */
    private static <T> T[] sampleArray(T[] arr, int[] indices) {
        if (arr == null) return null;
        Class<?> type = arr.getClass().getComponentType();
        @SuppressWarnings("unchecked")
        T[] r = (T[]) Array.newInstance(type, indices.length);
        for (int i = 0; i < indices.length; ++i)
            r[i] = arr[indices[i]];
        return r;
    }

    /**
     * Sample `arr` using `indices`.
     * Example: sampleArray({1.1, 2.2, 3.3}, {1, 0, 2, 2}) -> {2.2, 1.1, 3.3, 3.3}.
     */
    private static double[] sampleNums(double[] arr, int[] indices) {
        if (arr == null) return null;
        double[] r = new double[indices.length];
        for (int i = 0; i < indices.length; ++i)
            r[i] = arr[indices[i]];
        return r;
    }

    public String customFieldsBlock() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> field : customFields.entrySet()) {
            out.append(cleanStringField(field.getKey()));
            out.append(":=");
            out.append(cleanStringField(field.getValue()));
            out.append('\n');
        }
        return out.toString();
    }

    public void newSpaceUnits() {
        spaceUnits = new String[spaceDim];
    }

    public void newSpaceOrigin() {
        spaceOrigin = new double[spaceDim];
        for (int i = 0; i < spaceDim; ++i)
            spaceOrigin[i] = Double.NaN;
    }

    public void newSpaceDirections(boolean fillWithZeros) {
        spaceDirections = new double[spaceDim][spaceDim];
        if (fillWithZeros) return;
        for (int i = 0; i < spaceDim; ++i)
            for (int j = 0; j < spaceDim; ++j)
                spaceDirections[i][j] = Double.NaN;
    }

    /**
     * Check that all mandatory fields have been given values.
     * @throws NrrdException    When a mandatory field is left unspecified.
     */
    void verifyMandatoryFields() throws NrrdException {
        if (fileType == UNDEFINED_TYPE)
            throw new NrrdException("'type' field must be specified.");
        if (axes == null)
            throw new NrrdException("'dimension' field must be specified.");
        for (NrrdAxis axis : axes)
            if (axis.size <= 0) throw new NrrdException("'size' field must be specified.");
        if (compression == COMPRESSION_UNKNOWN)
            throw new NrrdException("'encoding' field must be specified.");
        if ((compression == GZIP || compression == BZIP2) && longOffset == -1)
            throw new NrrdException("'byte skip' cannot be -1 when compression is enabled.");
    }

    /**
     * Figure out which axes correspond to channel, X, Y, Z, and time after reading a header.
     * The general strategy for finding the shape:
     * 1. Is the number of axes consistent with this axis order?
     * 2. Is `spaceDims` defined? Space is ALWAYS ordered as X,Y,Z,T.
     * 3. Are `kinds` defined for the axes in a consistent way?
     * 4. Fallback on a basic assumption: ZTC is the preferred order to add axes beyond XY.
     * Once determined, the axis indices are stored as cAxis, xAxis, yAxis, zAxis, and tAxis.
     * The sizes for each type of axis are also extracted into their respective fields,
     * the axes are assigned their respective types, and nImages is calculated.
     */
    public void resolveShape() {
        switch (axes.length) {
            case 1:  // X
                if (IJ.debugMode) IJ.log("1D NRRD axes: X");
                xAxis = 0;
                break;
            case 2:  // XY
                if (IJ.debugMode) IJ.log("2D NRRD axes: XY");
                xAxis = 0;
                yAxis = 1;
                break;
            case 3:  // CXY, XYZ, XYT
                if (spaceDim != 3) {  // CXY or XYT?
                    // Is it is possible to be either of these?
                    boolean maybeChannelFirst = axes[0].kind == null ||
                            !stringEqualsAny(axes[0].kind.toLowerCase(), "domain", "space", "time");
                    boolean definitelyTimeLast = "time".equalsIgnoreCase(axes[2].kind);
                    if (maybeChannelFirst && !definitelyTimeLast) {  // CXY
                        if (IJ.debugMode) IJ.log("3D NRRD axes: CXY");
                        cAxis = 0;
                        xAxis = 1;
                        yAxis = 2;
                        break;
                    }
                    if (!maybeChannelFirst && definitelyTimeLast) {  // XYT
                        if (IJ.debugMode) IJ.log("3D NRRD axes: XYT");
                        xAxis = 0;
                        yAxis = 1;
                        tAxis = 2;
                        break;
                    }
                }
                // Default to XYZ.
                if (IJ.debugMode) IJ.log("3D NRRD axes: XYZ");
                xAxis = 0;
                yAxis = 1;
                zAxis = 2;
                break;
            case 4:  // CXYZ, CXYT, XYZT
                boolean maybeChannelFirst = axes[0].kind == null ||
                        !stringEqualsAny(axes[0].kind.toLowerCase(), "domain", "space", "time");
                if (spaceDim != 4 && maybeChannelFirst) {  // CXYZ or CXYT
                    cAxis = 0;
                    xAxis = 1;
                    yAxis = 2;
                    if ("time".equalsIgnoreCase(axes[3].kind)) {  // CXYT
                        if (IJ.debugMode) IJ.log("4D NRRD axes: CXYT");
                        tAxis = 3;
                    } else {  // CXYZ
                        if (IJ.debugMode) IJ.log("4D NRRD axes: CXYZ");
                        zAxis = 3;
                    }
                } else {
                    // Default to XYZT.
                    if (IJ.debugMode) IJ.log("4D NRRD axes: XYZT");
                    xAxis = 0;
                    yAxis = 1;
                    zAxis = 2;
                    tAxis = 3;
                }
                break;
            case 5:  // CXYZT
                if (IJ.debugMode) IJ.log("5D NRRD axes: CXYZT");
                cAxis = 0;
                xAxis = 1;
                yAxis = 2;
                zAxis = 3;
                tAxis = 4;
                break;
        }
        if (cAxis > -1) {
            nChannels = axes[cAxis].size;
            axes[cAxis].type = NrrdAxis.CHANNEL;
        }
        if (xAxis > -1) {
            width = axes[xAxis].size;
            axes[xAxis].type = NrrdAxis.X;
        }
        if (yAxis > -1) {
            height = axes[yAxis].size;
            axes[yAxis].type = NrrdAxis.Y;
        }
        if (zAxis > -1) {
            nSlices = axes[zAxis].size;
            axes[zAxis].type = NrrdAxis.Z;
        }
        if (tAxis > -1) {
            nFrames = axes[tAxis].size;
            axes[tAxis].type = NrrdAxis.TIME;
        }
        nImages = nChannels * nSlices * nFrames;
        solveSpaceIndices();
    }

    /**
     * Take the solved axis indices and determine the space axis indices as a result.
     * Space axes are ALWAYS ordered X, Y, Z, T, however, some axes may be missing.
     * Note that these might instead be interpreted as LPST, etc., depending on `space`.
     * @see <a href="https://teem.sourceforge.net/nrrd/format.html#space">NRRD Space Specification</a>
     */
    protected void solveSpaceIndices() {
        xSpaceAxis = 0;
        ySpaceAxis = yAxis > -1 ? 1 : -1;
        zSpaceAxis = zAxis > -1 ? zAxis - xAxis : -1;
        tSpaceAxis = tAxis > -1 ? tAxis - xAxis : -1;
    }

    /**
     * Make an input stream, taking into account the file path specified and the compression method.
     * The appropriate amount of skips will also be applied.
     * @return  The input stream created, either a random-access file or a decompression stream.
     * @throws IOException  When the stream cannot be created or read from.
     */
    public InputStream makeInputStream() throws IOException {
        if (inputStream != null) return inputStream;

        File f = new File(getFilePath());
        if (!f.isFile()) return null;
        inputStream = Files.newInputStream(f.toPath());

        if (!headless) skipInputBytes(headerLength);
        skipInputLines();

        if (compression == GZIP)
            inputStream = new GZIPInputStream(inputStream, 50000);

        // Byte skipping must be applied AFTER building the compression stream.
        // -1 is a special value of 'byte skip' indicating to read starting relative to the end of the file.
        // The -1 notation cannot be used with compression, and is handled by a previous error check.
        if (longOffset == -1) {
            long skip = f.length() - byteSize();
            if (!headless) skip -= headerLength;
            if (skip < 0) throw new IOException("EOF - file is not long enough to contain the specified image data.");
            skipInputBytes(skip);
        } else skipInputBytes(longOffset);

        return inputStream;
    }

    /**
     * Skip `lineSkip` lines on the input stream.
     * A line is defined as zero or more bytes followed by '\n', '\r', or '\r\n'.
     * @throws IOException  When the skip fails, or InputStream's `skip` method throws an exception.
     */
    private void skipInputLines() throws IOException {
        PushbackInputStream s;
        if (inputStream instanceof PushbackInputStream) s = (PushbackInputStream) inputStream;
        else inputStream = s = new PushbackInputStream(inputStream, 1);

        long linesRemaining = lineSkip;
        while (linesRemaining > 0L) {
            switch (s.read()) {
                case -1: throw new IOException("EOF - failed to seek to skip " + lineSkip + " lines within the file.");
                case '\r':
                    int next = s.read();
                    if (next != '\n' && next != -1) s.unread(next);
                case '\n': --linesRemaining;
            }
        }
    }

    /**
     * Skip bytes on the input stream, trying multiple times in case of failure before giving up.
     * @param skip  The number of bytes to skip.
     * @throws IOException  When the skip fails, or InputStream's `skip` method throws an exception.
     */
    private void skipInputBytes(long skip) throws IOException {
        int skipAttempts = 0;
        long bytesRemaining = skip;
        while (bytesRemaining > 0) {
            if (IJ.debugMode) IJ.log("Skipping " + bytesRemaining + " bytes.");
            bytesRemaining -= inputStream.skip(bytesRemaining);
            ++skipAttempts;
            if (skipAttempts > 5)
                throw new IOException("EOF - failed to seek to byte " + skip + " within the file.");
        }
    }

    /**
     * Get the data type associated with a name.
     * @param name  A lower-case exactly-matching alias of the file type.
     * @return      The FileInfo-compatible code corresponding, or -1 if no match was found.
     */
    private static int parseDataType(String name) throws NrrdException {
        if (name.equals("block"))
            throw new NrrdException("'block' data type is not supported.");
        for (int i = 0; i < DATA_TYPES.length; i++)
            for (String alias : DATA_TYPE_ALIASES[i])
                if (alias.equals(name)) return DATA_TYPES[i];
        throw new NrrdException("Unrecognized data type, '" + name + "'.");
    }

    /**
     * Get the data name associated with a data type.
     * @param dataType  The data type code.
     * @return          The name of the dataType, or null if the type is unsupported.
     */
    public static String dataName(int dataType) {
        for (int i = 0; i < DATA_TYPES.length; i++)
            if (dataType == DATA_TYPES[i]) return DATA_TYPE_ALIASES[i][0];
        return null;
    }

    /**
     * Get the enum value corresponding to this string.
     * @param encoding          The lowercase string representing the encoding.
     * @return                  The enum value of the corresponding encoding.
     * @throws NrrdException    When the encoding is not recognizable, or this implementation doesn't support it.
     * @see <a href="https://teem.sourceforge.net/nrrd/format.html#encoding">NRRD Encoding Specification</a>
     */
    private static int parseEncoding(String encoding) throws NrrdException {
        switch (encoding) {
            case "raw":
                return RAW;
            case "gz":
            case "gzip":
                return GZIP;
            case "bz2":
            case "bzip2":
                throw new NrrdException("The BZip2 encoding is not supported.");
                //return BZIP2;
            case "hex":
                throw new NrrdException("The hexadecimal encoding is not supported.");
                //return HEX;
            case "txt":
            case "text":
            case "ascii":
                throw new NrrdException("The ASCII encoding is not supported.");
                //return TEXT;
            default:
                throw new NrrdException("Unrecognized encoding, '" + encoding + "'.");
        }
    }

    /**
     * Get the data name associated with a data type.
     * @param encoding  The encoding code, called "compression" in FileInfo.
     * @return          The name of the encoding, or "raw" if the encoding is unsupported.
     */
    public static String encodingName(int encoding) {
        switch (encoding) {
            case GZIP:  return "gzip";
            case BZIP2: return "bzip2";
            case HEX:   return "hex";
            case TEXT:  return "text";
            default:    return "raw";
        }
    }

    /**
     * Get the enum value corresponding to this string.
     * @param endian            The lowercase string representing the endian.
     * @return                  true when little endian, false when big.
     * @throws NrrdException    When the endian value is not recognizable.
     * @see <a href="https://teem.sourceforge.net/nrrd/format.html#endian">NRRD Endian Specification</a>
     */
    private static boolean parseEndian(String endian) throws NrrdException {
        switch (endian) {
            case "little":
                return true;  // Intel byte order.
            case "big":
                return false;
            default:
                throw new NrrdException("Endianness, '" + endian + "', should be 'little' or 'big'.");
        }
    }

    /**
     * Get the enum value corresponding to this string.
     * @param centering         The lowercase string representing the centering.
     * @return                  The enum value of the corresponding centering.
     * @throws NrrdException    When the centering is not recognizable.
     * @see <a href="https://teem.sourceforge.net/nrrd/format.html#centers">NRRD Centers Specification</a>
     */
    private static int parseCentering(String centering) throws NrrdException {
        if (centering == null) return NrrdAxis.CENTER_UNKNOWN;
        switch (centering) {
            case "cell":
                return NrrdAxis.CENTER_CELL;
            case "node":
                return NrrdAxis.CENTER_NODE;
            default:
                throw new NrrdException("Unrecognised centering scheme: '" + centering + "'");
        }
    }

    /**
     * Get the number of space dimensions corresponding to this space.
     * @param space             The string representing the space.
     * @return                  The number of space dimensions required by this space.
     * @throws NrrdException    When the space is unrecognizable.
     * @see <a href="https://teem.sourceforge.net/nrrd/format.html#space">NRRD Space Specification</a>
     */
    private static int parseSpaceDim(String space) throws NrrdException {
        switch (space.toLowerCase()) {
            case "right-anterior-superior":
            case "ras":
            case "left-anterior-superior":
            case "las":
            case "left-posterior-superior":
            case "lps":
            case "scanner-xyz":
            case "3d-right-handed":
            case "3d-left-handed":
                return 3;
            case "right-anterior-superior-time":
            case "rast":
            case "left-anterior-superior-time":
            case "last":
            case "left-posterior-superior-time":
            case "lpst":
            case "scanner-xyz-time":
            case "3d-right-handed-time":
            case "3d-left-handed-time":
                return 4;
            default:
                throw new NrrdException("Unrecognised coordinate space, '" + space + "'.");
        }
    }

    /**
     * Get the axis size implied by this kind.
     * @param kind              The string representing the kind, case-insensitive.
     * @return                  The compatible axis size, or 0 if any size is permitted.
     * @throws NrrdException    When the kind is unrecognizable.
     * @see <a href="https://teem.sourceforge.net/nrrd/format.html#kind">NRRD Kind Specification</a>
     */
    private static int kindAxisSize(String kind) throws NrrdException {
        if (kind == null) return 0;
        switch (kind.toLowerCase()) {
            case "domain":
            case "space":
            case "time":
            case "list":
            case "point":
            case "vector":
            case "covariant-vector":
            case "normal":
                return 0;  // Unspecified size.
            case "stub":
            case "scalar":
                return 1;
            case "complex":
            case "2-vector":
                return 2;
            case "3-color":
            case "rgb-color":
            case "hsv-color":
            case "xyz-color":
            case "3-vector":
            case "3-gradient":
            case "3-normal":
            case "2d-symmetric-matrix":
                return 3;
            case "4-color":
            case "rgba-color":
            case "4-vector":
            case "quaternion":
            case "2d-masked-symmetric-matrix":
            case "2d-matrix":
                return 4;
            case "2d-masked-matrix":  // This is erroneously identified in the specification as 4D.
                return 5;
            case "3d-symmetric-matrix":
                return 6;
            case "3d-masked-symmetric-matrix":
                return 7;
            case "3d-matrix":
                return 9;
            case "3d-masked-matrix":
                return 10;
            default:
                throw new NrrdException("Unrecognised kind, '" + kind + "'.");
        }
    }

    /**
     * Does this line contain a non-standard, i.e., user-defined field?
     * @param line  A line from the NRRD file.
     * @return      Does this line contain a user-defined object symbol?
     */
    private static boolean fieldIsCustom(String line) {
        return line.contains(":=");
    }

    /**
     * Get the name of a field from its line, i.e., the part before the ": " or ":=".
     * @param line  A line from the NRRD file.
     * @return      The field name, i.e., the part just before the ": " or ":=".
     */
    private static String parseFieldType(String line) {
        // := is never a valid part of a standard field, so check this first.
        int i = line.indexOf(":=");
        if (i < 0) i = line.indexOf(": ");
        if (i < 0) return "";
        return line.substring(0, i).trim();
    }

    /**
     * Get the value of a field from its line, i.e., the part after the ": " or ":=".
     * @param line  A non-newline-terminated line from the NRRD file.
     * @return      The field contents, i.e., the part just after the ": " or ":=".
     */
    private static String parseFieldValue(String line) {
        // := is never a valid part of a standard field, so check this first.
        int i = line.indexOf(":=");
        if (i < 0) i = line.indexOf(": ");
        if (i < 0) return "";
        // Do NOT trim this part. Spaces are valid parts of a custom field.
        return line.substring(i + 2);
    }

    /**
     * Split the field values by empty space, respecting and extracting the groups indicated by double-quotes.
     * Values like "none" indicating an empty field are given the value null.
     * @param fieldValue   The part of the field containing its value.
     * @return             An array of field values split by empty space and unquoted.
     * @see NrrdFileInfo#parseFieldValue(String line)
     * @see <a href="https://teem.sourceforge.net/nrrd/format.html#spaceorigin">NRRD Vector Specification</a>
     */
    private static String[] parseSubfields(String fieldValue) {
        ArrayList<String> subfields = new ArrayList<>();
        Matcher matcher = SUBFIELD_PAT.matcher(fieldValue);
        while (matcher.find()) {
            String subfield = matcher.group(1);
            if (stringIsNone(subfield)) subfields.add(null);
            else {
                if (subfield.startsWith("\""))
                    subfield = subfield.substring(1, subfield.length() - 1);
                subfields.add(subfield);
            }
        }
        String[] subfieldArray = new String[subfields.size()];
        subfields.toArray(subfieldArray);
        return subfieldArray;
    }

    /**
     * Parse the field value as a space-separated list of real-valued vectors, or "none" for null.
     * @param fieldValue   The part of the field containing its value.
     * @return             An array of double vectors, or nulls.
     * @see NrrdFileInfo#parseFieldValue(String line)
     * @see <a href="https://teem.sourceforge.net/nrrd/format.html#spaceorigin">NRRD Vector Specification</a>
     */
    private static double[][] parseVectors(String fieldValue) throws NumberFormatException {
        ArrayList<double[]> vectors = new ArrayList<>();
        Matcher matcher = VECTOR_PAT.matcher(fieldValue);
        while (matcher.find()) {
            String vectorStr = matcher.group(1);
            if (stringIsNone(vectorStr)) {
                vectors.add(null);
                continue;
            }
            vectorStr = vectorStr.substring(1, vectorStr.length() - 1).trim();
            String[] numberStrings = VECTOR_SPLIT_PAT.split(vectorStr);
            double[] numbers = new double[numberStrings.length];
            for (int i = 0; i < numbers.length; i++) {
                numbers[i] = parseDouble(numberStrings[i]);
            }
            vectors.add(numbers);
        }
        double[][] vectorsArray = new double[vectors.size()][];
        vectors.toArray(vectorsArray);
        return vectorsArray;
    }

    /**
     * Compare a string to a fixed list of other strings and check if any are equal, ignoring case.
     * @param s     The string to compare (can be null).
     * @param sAny  The strings to compare against (cannot be null).
     * @return      True if any of the strings matched, false otherwise.
     */
    private static boolean stringEqualsAny(String s, final String ... sAny) {
        for (String x : sAny)
            if (x.equalsIgnoreCase(s)) return true;
        return false;
    }

    /**
     * @return  true when `s` contains a value which should be parsed as null.
     */
    private static boolean stringIsNone(String s) {
        return stringEqualsAny(s, "", "none", "???");
    }

    /**
     * Parse a double in the sense specified for NRRD files, that is, respecting NaN and "inf" for infinity.
     * This also accepts all the regular Java interpretations, like "Infinity", which might not be recognized
     * by the NRRD specification.
     * Unlike the NRRD specification, simply containing the substring "nan" is not good enough; the string must
     * exactly equal its intended value, ignoring the case.
     * @param s A string encoding a double value.
     * @return  The double value decoded, including NaN or +/-infinity.
     */
    private static double parseDouble(String s) {
        if ("nan".equalsIgnoreCase(s)) return Double.NaN;
        if ("inf".equalsIgnoreCase(s) || "+inf".equalsIgnoreCase(s)) return Double.POSITIVE_INFINITY;
        if ("-inf".equalsIgnoreCase(s)) return Double.NEGATIVE_INFINITY;
        return Double.parseDouble(s);
    }

    /**
     * Remove problematic characters from a potential header field string.
     * Problematic characters include newline, which is used for syntax when parsing.
     * Example of an illegal field:
     *      content: This content line contains
     *      multiple lines.
     * @param s The original field value to be cleaned, or an empty string, if null.
     * @return  The cleaned field value, lacking any newlines.
     */
    private static String cleanStringField(String s) {
        if (s == null) return "";
        return s.replace('\n', ' ');
    }

    /**
     * Double quote a header subfield string, removing any problematic characters.
     * Problematic characters include newline and double-quote, which are used for syntax when parsing.
     * Example of an illegal subfield:
     *      labels: "label 1" "don"t mind this double quote" "label 3"
     * Technically, this should be handled by escaping the quote character, but we don't support that.
     * @param s Value of the subfield, "none" if null.
     * @return  The cleaned subfield, enclosed in double quotes.
     */
    private static String stringSubfield(String s) {
        if (s == null) return "none";
        s = s.replace('\n', ' ');
        s = s.replace('"', '\'');
        return "\"" + s + "\"";
    }

    /**
     * Convert a number to a string, handling NaNs and infinite values according to the NRRD standard.
     * @param x The number to be converted to a String.
     * @return  The string representation of the number.
     * @see <a href="https://teem.sourceforge.net/nrrd/format.html#ascii">NRRD Numeric ASCII Specification</a>
     */
    private static String doubleSubfield(double x) {
        if (Double.isNaN(x)) return "nan";
        if (Double.isInfinite(x)) return x > 0 ? "inf" : "-inf";
        String r = "" + x;
        if (r.endsWith(".0")) return r.substring(0, r.length() - 2);
        return r;
    }

    /**
     * Convert a vector of numbers to a string.
     * @param vec   The number to be converted to a String.
     * @return      The string representation of the vector.
     * @see <a href="https://teem.sourceforge.net/nrrd/format.html#spacedirections">NRRD Vector Specification</a>
     */
    private static String vectorSubfield(double[] vec) {
        if (vec == null || !anyNumExists(vec)) return "none";
        StringBuilder r = new StringBuilder("(");
        for (int i = 0; i < vec.length; ++i) {
            if (i > 0) r.append(',');
            r.append(doubleSubfield(vec[i]));
        }
        r.append(')');
        return r.toString();
    }

    /**
     * @return true when any number in `arr` is not NaN.
     */
    private static boolean anyNumExists(double[] arr) {
        for (double x : arr) if (!Double.isNaN(x)) return true;
        return false;
    }

    /**
     * @return true when any value in `arr` is not null.
     */
    private static <T> boolean anyExists(T[] arr) {
        for (T x : arr)
            if (x != null && (!(x instanceof double[]) || anyNumExists((double[]) x)))
                return true;
        return false;
    }
}

/**
 * For reporting errors encountered when reading or writing an NRRD file.
 * @author andyf 2024-04
 */
class NrrdException extends RuntimeException {
    public int lineNum = -1;

    public NrrdException(String message) {
        super(message);
    }

    public NrrdException(String message, int lineNumber) {
        super(message);
        lineNum = lineNumber;
    }

    /**
     * Report the message via ImageJ's `log` and `error` methods.
     * @see IJ#log(String)
     * @see IJ#error(String, String)
     */
    public void report() {
        reportAs(getMessage());
    }

    /**
     * Report the message via ImageJ's `log` and `error` methods, with an additional warning when the header version
     * exceeds the supported version.
     * @see IJ#log(String)
     * @see IJ#error(String, String)
     */
    public void report(int headerVersion) {
        String message = getMessage();
        if (headerVersion > NrrdFileInfo.SUPPORTED_NRRD_VERSION)
            message += "\nThis NRRD file reports version number " + headerVersion
                    + " which is newer than " + NrrdFileInfo.SUPPORTED_NRRD_VERSION + ", the supported version.";
        reportAs(message);
    }

    /**
     * Report the message passed via ImageJ's `log` and `error` methods.
     * Also clear the ImageJ status bar.
     */
    private void reportAs(String message) {
        if (lineNum == -1) {
            IJ.log("NRRD error: " + message);
            IJ.error("NRRD error", message);
        } else {
            IJ.log("NRRD error on line " + lineNum + ": " + message);
            IJ.error("NRRD error on line " + lineNum, message);
        }
        IJ.showStatus("");
    }
}