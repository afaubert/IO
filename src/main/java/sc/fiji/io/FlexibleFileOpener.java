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

// FlexibleFileOpener
// ------------------
// Class to allow ImageJ plugins to semi-transparently access
// compressed (GZIP, ZLIB) raw image data.
// Used by NrrdReader.
// 
// - It can add a GZIPInputStream or ZInputStream onto the
// stream provided to File opener
// - Can also specify a pre-offset to jump to before FileOpener sees the 
// stream.  This allows one to read compressed blocks from a file that
// has not been completely compressed. 
//
// NB: GZIP is not the same as ZLIB; GZIP has a longer header.
// The compression algorithm is identical.

// (c) Gregory Jefferis 2007
// Department of Zoology, University of Cambridge
// jefferis@gmail.com
// All rights reserved.
// Source code released under Lesser GNU Public License v2.

import com.jcraft.jzlib.ZInputStream;

import ij.IJ;
import ij.io.FileInfo;
import ij.io.FileOpener;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

class FlexibleFileOpener extends FileOpener {
	public static final int UNCOMPRESSED = 0;
	public static final int GZIP = 1;
	public static final int ZLIB = 2;
	
	int gUnzipMode;
	// The offset that will be skipped before FileOpener sees the stream
	long preOffset = 0;

	public FlexibleFileOpener(FileInfo fi, int gUnzipMode) {
		this(fi, gUnzipMode,0);
	}
	
	public FlexibleFileOpener(FileInfo fi, int gUnzipMode, long preOffset) {
		super(fi);
		this.gUnzipMode = gUnzipMode;
		this.preOffset = preOffset;
	}
	
	public InputStream createInputStream(FileInfo fi) throws IOException {
		// Use the method in the FileOpener class to generate an input stream
		InputStream is = super.createInputStream(fi);

		is.skip(preOffset);
		switch (gUnzipMode) {
			case UNCOMPRESSED:
				return is;
			case GZIP:
				if (is instanceof GZIPInputStream) return is;
				return new GZIPInputStream(is,50000);
			case ZLIB:
				return new ZInputStream(is);  // From jzlib, deprecated
			default:
				throw new IOException("Incorrect GZIP mode: " + gUnzipMode);
		}
	}
}
