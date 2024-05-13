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

/**
 * A storage class representing all the parameters associated with an axis of an NRRD file.
 * @author Andre Faubert 2024-04
 */
public class NrrdAxis {
    // Enum values for `center`.
    public static final int CENTER_UNKNOWN = 0;
    public static final int CENTER_NODE = 1;
    public static final int CENTER_CELL = 2;

    public int size = 0;
    public double spacing = Double.NaN;
    public double thickness = Double.NaN;
    public int center = CENTER_UNKNOWN;
    public double min = Double.NaN;
    public double max = Double.NaN;
    public String unit = null;
    public String label = null;
    public String kind = null;

    // Fields for controlling how the axis is written to a file:

    // The "type" enum/index values.
    public static final int AXIS_UNKNOWN = -1;
    public static final int CHANNEL = 0;
    public static final int X = 1;
    public static final int Y = 2;
    public static final int Z = 3;
    public static final int TIME = 4;

    // An index into the canonical C, X, Y, Z, T axis ordering.
    // This is NOT the index into an NrrdFileInfo's `axes` array.
    public int type = AXIS_UNKNOWN;
    // Names corresponding to each axis type.
    public static final String[] NAMES = {"channel", "x", "y", "z", "time"};
    public static final char[] LETTERS = {'C', 'X', 'Y', 'Z', 'T'};

    // When true, this axis should be "sliced" when writing, that is, multiple NRRD files should be written,
    // each containing a single sample from this axis.
    public boolean slice = false;
    // The slice index indicates which slice to write pixel data for.
    public int sliceIndex = -1;

    public NrrdAxis() {
    }

    public NrrdAxis(int type) {
        this.type = type;
    }

    public String toString() {
        return "NrrdAxis(type=" + type + " (" + name() + ")"
                + ", size=" + size
                + ", spacing=" + spacing
                + ", thickness=" + thickness
                + ", spacing=" + spacing
                + ", center=" + center + " (" + centerText() + ")"
                + ", axisMin=" + min
                + ", axisMax=" + max
                + ", unit=" + unit
                + ", label=" + label
                + ", kind=" + kind
                + ", slice=" + slice
                + ", sliceIndex=" + sliceIndex
                + ")";
    }

    /**
     * @return The canonical name of this axis.
     */
    public String name() {
        if (type == AXIS_UNKNOWN) return "UNKNOWN";
        return NAMES[type];
    }

    /**
     * @return The letter prefixing the canonical name of this axis.
     */
    public char letter() {
        if (type == AXIS_UNKNOWN) return ' ';
        return LETTERS[type];
    }

    /**
     * @return How many decimal digits are needed to represent the `size` field.
     */
    public int digitsForSize() {
        if (size <= 0) return 1;
        return (int) Math.ceil(Math.log10(size + 1));
    }

    /**
     * @return The text representation of the `center` enum value.
     */
    public String centerText() {
        switch (center) {
            case NrrdAxis.CENTER_NODE: return "node";
            case NrrdAxis.CENTER_CELL: return "cell";
            default: return "none";
        }
    }
}
