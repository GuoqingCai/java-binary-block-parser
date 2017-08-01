/*
 * Copyright 2017 Igor Maznitsa.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.igormaznitsa.jbbp.compiler.conversion;

import static com.igormaznitsa.jbbp.TestUtils.assertPngChunk;
import static com.igormaznitsa.jbbp.TestUtils.getField;
import static com.igormaznitsa.jbbp.TestUtils.wavInt2Str;
import org.junit.Test;
import com.igormaznitsa.jbbp.JBBPParser;
import com.igormaznitsa.jbbp.testaux.AbstractJavaClassCompilerTest;
import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.apache.commons.io.IOUtils;
import com.igormaznitsa.jbbp.io.JBBPBitInputStream;
import com.igormaznitsa.jbbp.io.JBBPBitOutputStream;

/**
 * Test reading writing with converted classes from parser.
 */
public class ParserToJavaClassConverterReadWriteTest extends AbstractJavaClassCompilerTest {

  protected static final String PACKAGE_NAME = "com.igormaznitsa.test";
  protected static final String CLASS_NAME = "TestClass";
  private static final Random RND = new Random(123456);

  private byte[] loadResource(final String name) throws Exception {
    final InputStream result = this.getClass().getClassLoader().getResourceAsStream("com/igormaznitsa/jbbp/it/" + name);
    try {
      if (result == null) {
        throw new NullPointerException("Can't find resource '" + name + '\'');
      }
      return IOUtils.toByteArray(result);
    }
    finally {
      IOUtils.closeQuietly(result);
    }
  }

  private Object compileAndMakeInstance(final String script) throws Exception {
    return compileAndMakeInstance(PACKAGE_NAME + '.' + CLASS_NAME, new JavaClassContent[]{new JavaClassContent(PACKAGE_NAME + '.' + CLASS_NAME, JBBPParser.prepare(script).makeClassSrc(PACKAGE_NAME, CLASS_NAME))});
  }

  private Object compileAndMakeInstance(final String instanceClassName, final JavaClassContent[] classContents) throws Exception {
    final ClassLoader cloader = saveAndCompile(classContents);
    return cloader.loadClass(instanceClassName).newInstance();
  }

  private Object callRead(final Object instance, final byte[] array) throws Exception {
    instance.getClass().getMethod("read", JBBPBitInputStream.class).invoke(instance, new JBBPBitInputStream(new ByteArrayInputStream(array)));
    return instance;
  }

  private byte[] callWrite(final Object instance) throws Exception {
    final ByteArrayOutputStream bout = new ByteArrayOutputStream();
    instance.getClass().getMethod("write", JBBPBitOutputStream.class).invoke(instance, new JBBPBitOutputStream(bout));
    bout.close();
    return bout.toByteArray();
  }

  @Test
  public void testReadWite_ByteArrayWholeStream() throws Exception {
    final Object instance = compileAndMakeInstance("byte [_] byteArray;");
    assertNull("by default must be null", getField(instance, "bytearray", byte[].class));

    final byte[] etalon = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 22, 33, 44, 55, 66};

    callRead(instance, etalon.clone());

    assertArrayEquals(etalon, getField(instance, "bytearray", byte[].class));
    assertArrayEquals(etalon, callWrite(instance));
  }

  @Test
  public void testReadWite_BitArrayWholeStream() throws Exception {
    final Object instance = compileAndMakeInstance("bit [_] bitArray;");
    assertNull("by default must be null", getField(instance, "bitarray", byte[].class));

    final byte[] etalon = new byte[1024];
    RND.nextBytes(etalon);

    callRead(instance, etalon.clone());

    assertEquals(etalon.length * 8, getField(instance, "bitarray", byte[].class).length);
    assertArrayEquals(etalon, callWrite(instance));
  }

  @Test
  public void testReadWite_PNG() throws Exception {
    final Object instance = compileAndMakeInstance("long header;"
        + "// chunks\n"
        + "chunk [_]{"
        + "   int length; "
        + "   int type; "
        + "   byte[length] data; "
        + "   int crc;"
        + "}");
    final byte[] pngEtalon = loadResource("picture.png");
    final String[] chunkNames = new String[]{"IHDR", "gAMA", "bKGD", "pHYs", "tIME", "tEXt", "IDAT", "IEND"};
    final int[] chunkSizes = new int[]{0x0D, 0x04, 0x06, 0x09, 0x07, 0x19, 0x0E5F, 0x00};

    callRead(instance, pngEtalon.clone());

    assertEquals(0x89504E470D0A1A0AL, getField(instance, "header", Long.class).longValue());
    assertEquals(chunkNames.length, getField(instance, "chunk", Object[].class).length);

    int i = 0;
    for (final Object chunk : getField(instance, "chunk", Object[].class)) {
      assertPngChunk(chunkNames[i], chunkSizes[i], getField(chunk, "type", Integer.class), getField(chunk, "length", Integer.class), getField(chunk, "crc", Integer.class), getField(chunk, "data", byte[].class));
      i++;
    }

    assertArrayEquals(pngEtalon, callWrite(instance));
  }

  @Test
  public void testReadWite_WAV() throws Exception {
    final Object instance = compileAndMakeInstance("<int ChunkID;"
        + "<int ChunkSize;"
        + "<int Format;"
        + "SubChunks [_]{"
        + "  <int SubChunkID;"
        + "  <int SubChunkSize;"
        + "  byte [SubChunkSize] data;"
        + "  align:2;"
        + "}");

    final byte[] wavEtalon = loadResource("M1F1-float64WE-AFsp.wav");
    final String[] subchunkNames = new String[]{"fmt ", "fact", "data", "afsp", "LIST"};

    callRead(instance, wavEtalon.clone());

    assertEquals(0x46464952, getField(instance, "chunkid", Integer.class).intValue());
    assertEquals(0x45564157, getField(instance, "format", Integer.class).intValue());

    final Object[] subchunks = getField(instance, "subchunks", Object[].class);
    assertEquals("Number of parsed subchunks must be [" + subchunkNames.length + ']', subchunkNames.length, subchunks.length);

    int calculatedSize = 4;
    int index = 0;
    for (final Object subchunk : subchunks) {
      final String strChunkId = subchunkNames[index++];
      assertEquals("WAV subchunk must have 4 char length [" + strChunkId + ']', 4, strChunkId.length());
      assertEquals(strChunkId, wavInt2Str(getField(subchunk, "subchunkid", Integer.class)));
      final int subChunkSize = getField(subchunk, "subchunksize", Integer.class);
      assertEquals("Data array must have the same size as described in sub-chunk size field", subChunkSize, getField(subchunk, "data", byte[].class).length);
      calculatedSize += subChunkSize + 8 + (subChunkSize & 1);
    }

    assertEquals(calculatedSize, getField(instance, "chunksize", Integer.class).intValue());

    assertArrayEquals(wavEtalon, callWrite(instance));
  }

  @Test
  public void testReadWrite_SNA() throws Exception {
    final Object instance = compileAndMakeInstance("ubyte regI;"
        + "<ushort altHL; <ushort altDE; <ushort altBC; <ushort altAF;"
        + "<ushort regHL; <ushort regDE; <ushort regBC; <ushort regIY; <ushort regIX;"
        + "ubyte iff; ubyte regR;"
        + "<ushort regAF; <ushort regSP;"
        + "ubyte im;"
        + "ubyte borderColor;"
        + "byte [49152] ramDump;");

    final byte[] snaEtalon = loadResource("zexall.sna");

    callRead(instance, snaEtalon.clone());

    assertEquals(0x3F, getField(instance, "regi", Character.class).charValue());
    assertEquals(0x2758, getField(instance, "althl", Character.class).charValue());
    assertEquals(0x369B, getField(instance, "altde", Character.class).charValue());
    assertEquals(0x1721, getField(instance, "altbc", Character.class).charValue());
    assertEquals(0x0044, getField(instance, "altaf", Character.class).charValue());

    assertEquals(0x2D2B, getField(instance, "reghl", Character.class).charValue());
    assertEquals(0x80ED, getField(instance, "regde", Character.class).charValue());
    assertEquals(0x803E, getField(instance, "regbc", Character.class).charValue());
    assertEquals(0x5C3A, getField(instance, "regiy", Character.class).charValue());
    assertEquals(0x03D4, getField(instance, "regix", Character.class).charValue());

    assertEquals(0x00, getField(instance, "iff", Character.class).charValue());
    assertEquals(0x0AE, getField(instance, "regr", Character.class).charValue());

    assertEquals(0x14A1, getField(instance, "regaf", Character.class).charValue());
    assertEquals(0x7E62, getField(instance, "regsp", Character.class).charValue());

    assertEquals(0x01, getField(instance, "im", Character.class).charValue());
    assertEquals(0x07, getField(instance, "bordercolor", Character.class).charValue());

    assertEquals(49152, getField(instance, "ramdump", byte[].class).length);

    assertArrayEquals(snaEtalon, callWrite(instance));
  }

  @Test
  public void testReadWrite_TGA_noColormap() throws Exception {
    final Object instance = compileAndMakeInstance("Header {"
        + "          ubyte IDLength;"
        + "          ubyte ColorMapType;"
        + "          ubyte ImageType;"
        + "          <ushort CMapStart;"
        + "          <ushort CMapLength;"
        + "          ubyte CMapDepth;"
        + "          <short XOffset;"
        + "          <short YOffset;"
        + "          <ushort Width;"
        + "          <ushort Height;"
        + "          ubyte PixelDepth;"
        + "          ImageDesc {"
        + "              bit:4 PixelAttrNumber;"
        + "              bit:2 Pos;"
        + "              bit:2 Reserved;"
        + "          }"
        + "      }"
        + "byte [Header.IDLength] ImageID;"
        + "ColorMap [ (Header.ColorMapType & 1) * Header.CMapLength ] {"
        + "    byte [Header.CMapDepth >>> 3] ColorMapItem; "
        + " }"
        + "byte [_] ImageData;");

    final byte[] tgaEtalon = loadResource("cbw8.tga");

    callRead(instance, tgaEtalon.clone());

    assertEquals("Truevision(R) Sample Image".length(), getField(instance, "imageid", byte[].class).length);
    assertEquals(128, getField(instance, "header.width", Character.class).charValue());
    assertEquals(128, getField(instance, "header.height", Character.class).charValue());
    assertEquals(8, getField(instance, "header.pixeldepth", Character.class).charValue());
    assertEquals(0, getField(instance, "colormap", Object[].class).length);
    assertEquals(8715, getField(instance, "imagedata", byte[].class).length);

    assertArrayEquals(tgaEtalon, callWrite(instance));
  }

  @Test
  public void testReadWrite_TGA_hasColormap() throws Exception {
    final Object instance = compileAndMakeInstance("Header {"
        + "          ubyte IDLength;"
        + "          ubyte ColorMapType;"
        + "          ubyte ImageType;"
        + "          <ushort CMapStart;"
        + "          <ushort CMapLength;"
        + "          ubyte CMapDepth;"
        + "          <short XOffset;"
        + "          <short YOffset;"
        + "          <ushort Width;"
        + "          <ushort Height;"
        + "          ubyte PixelDepth;"
        + "          ImageDesc {"
        + "              bit:4 PixelAttrNumber;"
        + "              bit:2 Pos;"
        + "              bit:2 Reserved;"
        + "          }"
        + "      }"
        + "byte [Header.IDLength] ImageID;"
        + "ColorMap [ (Header.ColorMapType & 1) * Header.CMapLength ] {"
        + "    byte [Header.CMapDepth >>> 3] ColorMapItem; "
        + " }"
        + "byte [_] ImageData;");

    final byte[] tgaEtalon = loadResource("indexedcolor.tga");

    callRead(instance, tgaEtalon.clone());

    assertEquals("".length(), getField(instance, "imageid", byte[].class).length);
    assertEquals(640, getField(instance, "header.width", Character.class).charValue());
    assertEquals(480, getField(instance, "header.height", Character.class).charValue());
    assertEquals(8, getField(instance, "header.pixeldepth", Character.class).charValue());
    assertEquals(256, getField(instance, "colormap", Object[].class).length);
    assertEquals(155403, getField(instance, "imagedata", byte[].class).length);

    assertArrayEquals(tgaEtalon, callWrite(instance));
  }

  @Test
  public void testReadWrite_Z80v1() throws Exception {
    final Object instance = compileAndMakeInstance("byte reg_a; byte reg_f; <short reg_bc; <short reg_hl; <short reg_pc; <short reg_sp; byte reg_ir; byte reg_r; "
        + "flags{ bit:1 reg_r_bit7; bit:3 bordercolor; bit:1 basic_samrom; bit:1 compressed; bit:2 nomeaning;}"
        + "<short reg_de; <short reg_bc_alt; <short reg_de_alt; <short reg_hl_alt; byte reg_a_alt; byte reg_f_alt; <short reg_iy; <short reg_ix; byte iff; byte iff2;"
        + "emulFlags{bit:2 interruptmode; bit:1 issue2emulation; bit:1 doubleintfreq; bit:2 videosync; bit:2 inputdevice;}"
        + "byte [_] data;");

    final byte[] z80Etalon = loadResource("test.z80");

    callRead(instance, z80Etalon.clone());

    assertEquals((byte)0x7E, getField(instance, "reg_a", Byte.class).byteValue());
    assertEquals((byte)0x86, getField(instance, "reg_f", Byte.class).byteValue());
    assertEquals((short)0x7A74, getField(instance, "reg_bc", Short.class).shortValue());
    assertEquals((short)0x7430, getField(instance, "reg_hl", Short.class).shortValue());

    assertEquals((short)12198, getField(instance, "reg_pc", Short.class).shortValue());
    assertEquals((short)65330, getField(instance, "reg_sp", Short.class).shortValue());

    assertEquals((byte)0x3F, getField(instance, "reg_ir", Byte.class).byteValue());
    assertEquals((byte)0x1A, getField(instance, "reg_r", Byte.class).byteValue());

    assertEquals((byte)0, getField(instance, "flags.reg_r_bit7", Byte.class).byteValue());
    assertEquals((byte)2, getField(instance, "flags.bordercolor", Byte.class).byteValue());
    assertEquals((byte)0, getField(instance, "flags.basic_samrom", Byte.class).byteValue());
    assertEquals((byte)1, getField(instance, "flags.compressed", Byte.class).byteValue());
    assertEquals((byte)0, getField(instance, "flags.nomeaning", Byte.class).byteValue());

    assertEquals((short)0x742B, getField(instance, "reg_de", Short.class).shortValue());
    assertEquals((short)0x67C6, getField(instance, "reg_bc_alt", Short.class).shortValue());
    assertEquals((short)0x3014, getField(instance, "reg_de_alt", Short.class).shortValue());
    assertEquals((short)0x3461, getField(instance, "reg_hl_alt", Short.class).shortValue());

    assertEquals((byte)0x00, getField(instance, "reg_a_alt", Byte.class).byteValue());
    assertEquals((byte)0x46, getField(instance, "reg_f_alt", Byte.class).byteValue());

    assertEquals((short)0x5C3A, getField(instance, "reg_iy", Short.class).shortValue());
    assertEquals((short)0x03D4, getField(instance, "reg_ix", Short.class).shortValue());
    
    assertEquals((byte)0xFF, getField(instance, "iff", Byte.class).byteValue());
    assertEquals((byte)0xFF, getField(instance, "iff2", Byte.class).byteValue());

    assertEquals((byte)1, getField(instance, "emulflags.interruptmode", Byte.class).byteValue());
    assertEquals((byte)0, getField(instance, "emulflags.issue2emulation", Byte.class).byteValue());
    assertEquals((byte)0, getField(instance, "emulflags.doubleintfreq", Byte.class).byteValue());
    assertEquals((byte)0, getField(instance, "emulflags.videosync", Byte.class).byteValue());
    assertEquals((byte)0, getField(instance, "emulflags.inputdevice", Byte.class).byteValue());
    
    assertEquals(12399, getField(instance, "data", byte[].class).length);

    assertArrayEquals(z80Etalon, callWrite(instance));
  }
}