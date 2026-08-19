package com.github.ledlogic.imgutils;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Glb2Stl - Converts a .glb (binary glTF) file to a binary .stl file.
 *
 * Zero external dependencies. Java 8 compatible.
 *
 * Handles:
 *   - GLB container (JSON chunk + single BIN chunk)
 *   - Node hierarchy world-transform baking (TRS or matrix)
 *   - Mesh primitives with POSITION accessor (+ optional indices)
 *   - Accessor component types: FLOAT positions; UNSIGNED_BYTE/SHORT/INT indices
 *   - Merges every mesh primitive in the default scene into one STL
 *
 * Does NOT handle: sparse accessors, morph targets, skinning, draco compression,
 * external .bin/.gltf (use a .glb, not .gltf, as input).
 *
 * Usage:
 *   java Glb2Stl input.glb output.stl
 */
public class Glb2Stl {

    // ---------- CLI entry ----------

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Usage: java Glb2Stl <input.glb> [output.stl]");
            System.exit(1);
        }
        File inFile = new File(args[0]);
        File outFile = args.length == 2 ? new File(args[1]) : deriveStlName(inFile);

        try {
            byte[] glb = readAllBytes(inFile);
            GlbDocument doc = GlbDocument.parse(glb);
            List<Triangle> triangles = doc.collectTriangles();
            if (triangles.isEmpty()) {
                System.err.println("No triangle geometry found in " + inFile);
                System.exit(2);
            }
            writeBinaryStl(triangles, outFile, inFile.getName());
            System.out.println("Wrote " + triangles.size() + " triangles to " + outFile
                    + " (" + outFile.length() + " bytes)");
        } catch (Exception e) {
            System.err.println("Conversion failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(3);
        }
    }

    /** Derives "name.stl" next to the input file, e.g. model.glb -> model.stl. */
    private static File deriveStlName(File inFile) {
        String name = inFile.getName();
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        File parent = inFile.getParentFile(); // null when input has no directory component
        return parent == null ? new File(base + ".stl") : new File(parent, base + ".stl");
    }

    private static byte[] readAllBytes(File f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r");
             FileChannel ch = raf.getChannel()) {
            ByteBuffer buf = ByteBuffer.allocate((int) ch.size());
            while (buf.hasRemaining()) {
                if (ch.read(buf) < 0) break;
            }
            return buf.array();
        }
    }

    // ---------- STL writer ----------

    static void writeBinaryStl(List<Triangle> tris, File outFile, String sourceName) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(outFile)))) {
            byte[] header = new byte[80];
            String h = ("Converted from " + sourceName + " by Glb2Stl");
            byte[] hb = h.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(hb, 0, header, 0, Math.min(hb.length, 80));
            out.write(header);
            writeLeInt(out, tris.size());
            for (Triangle t : tris) {
                float[] n = t.normal();
                writeLeFloat(out, n[0]); writeLeFloat(out, n[1]); writeLeFloat(out, n[2]);
                for (float[] v : new float[][]{t.a, t.b, t.c}) {
                    writeLeFloat(out, v[0]); writeLeFloat(out, v[1]); writeLeFloat(out, v[2]);
                }
                out.writeByte(0); out.writeByte(0); // attribute byte count (unused)
            }
        }
    }

    private static void writeLeInt(DataOutputStream out, int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private static void writeLeFloat(DataOutputStream out, float f) throws IOException {
        writeLeInt(out, Float.floatToIntBits(f));
    }

    // ---------- Triangle ----------

    static final class Triangle {
        final float[] a, b, c;
        Triangle(float[] a, float[] b, float[] c) { this.a = a; this.b = b; this.c = c; }
        float[] normal() {
            float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
            float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
            float nx = uy * vz - uz * vy;
            float ny = uz * vx - ux * vz;
            float nz = ux * vy - uy * vx;
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-12) return new float[]{0, 0, 0};
            return new float[]{(float) (nx / len), (float) (ny / len), (float) (nz / len)};
        }
    }

    // ---------- 4x4 matrix (column-major, glTF convention) ----------

    static final class Mat4 {
        final double[] m; // 16, column-major
        Mat4(double[] m) { this.m = m; }

        static Mat4 identity() {
            return new Mat4(new double[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1});
        }

        static Mat4 fromTrs(double[] t, double[] q, double[] s) {
            // q = quaternion [x,y,z,w]
            double x = q[0], y = q[1], z = q[2], w = q[3];
            double x2 = x + x, y2 = y + y, z2 = z + z;
            double xx = x * x2, xy = x * y2, xz = x * z2;
            double yy = y * y2, yz = y * z2, zz = z * z2;
            double wx = w * x2, wy = w * y2, wz = w * z2;
            double sx = s[0], sy = s[1], sz = s[2];

            double[] m = new double[16];
            m[0] = (1 - (yy + zz)) * sx;
            m[1] = (xy + wz) * sx;
            m[2] = (xz - wy) * sx;
            m[3] = 0;

            m[4] = (xy - wz) * sy;
            m[5] = (1 - (xx + zz)) * sy;
            m[6] = (yz + wx) * sy;
            m[7] = 0;

            m[8] = (xz + wy) * sz;
            m[9] = (yz - wx) * sz;
            m[10] = (1 - (xx + yy)) * sz;
            m[11] = 0;

            m[12] = t[0];
            m[13] = t[1];
            m[14] = t[2];
            m[15] = 1;
            return new Mat4(m);
        }

        Mat4 multiply(Mat4 other) {
            double[] r = new double[16];
            double[] a = this.m, b = other.m;
            for (int col = 0; col < 4; col++) {
                for (int row = 0; row < 4; row++) {
                    double sum = 0;
                    for (int k = 0; k < 4; k++) {
                        sum += a[k * 4 + row] * b[col * 4 + k];
                    }
                    r[col * 4 + row] = sum;
                }
            }
            return new Mat4(r);
        }

        float[] transformPoint(float px, float py, float pz) {
            double x = m[0] * px + m[4] * py + m[8] * pz + m[12];
            double y = m[1] * px + m[5] * py + m[9] * pz + m[13];
            double z = m[2] * px + m[6] * py + m[10] * pz + m[14];
            return new float[]{(float) x, (float) y, (float) z};
        }
    }

    // ---------- GLB document model ----------

    static final class GlbDocument {
        JsonValue json;
        byte[] binChunk;

        static GlbDocument parse(byte[] glb) throws IOException {
            ByteBuffer buf = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
            int magic = buf.getInt();
            if (magic != 0x46546C67) { // "glTF"
                throw new IOException("Not a GLB file (bad magic). Input must be .glb, not .gltf.");
            }
            int version = buf.getInt();
            int length = buf.getInt();
            if (version != 2) {
                System.err.println("Warning: glTF version " + version + " (expected 2)");
            }

            GlbDocument doc = new GlbDocument();
            while (buf.remaining() >= 8) {
                int chunkLength = buf.getInt();
                int chunkType = buf.getInt();
                int start = buf.position();
                if (chunkType == 0x4E4F534A) { // "JSON"
                    byte[] jsonBytes = new byte[chunkLength];
                    buf.get(jsonBytes);
                    String jsonStr = new String(jsonBytes, StandardCharsets.UTF_8);
                    doc.json = new JsonParser(jsonStr).parseValue();
                } else if (chunkType == 0x004E4942) { // "BIN\0"
                    byte[] binBytes = new byte[chunkLength];
                    buf.get(binBytes);
                    doc.binChunk = binBytes;
                } else {
                    buf.position(start + chunkLength); // skip unknown chunk
                }
                // chunks are padded to 4-byte boundary; chunkLength already accounts for padding per spec
            }
            if (doc.json == null) throw new IOException("GLB has no JSON chunk");
            return doc;
        }

        List<Triangle> collectTriangles() {
            List<Triangle> tris = new ArrayList<>();
            JsonValue meshes = json.get("meshes");
            JsonValue accessors = json.get("accessors");
            JsonValue bufferViews = json.get("bufferViews");
            if (meshes == null || accessors == null || bufferViews == null) return tris;

            Map<Integer, Mat4> nodeWorldTransforms = computeNodeWorldTransforms();

            JsonValue nodes = json.get("nodes");
            if (nodes != null) {
                for (int i = 0; i < nodes.size(); i++) {
                    JsonValue node = nodes.get(i);
                    JsonValue meshIdx = node.get("mesh");
                    if (meshIdx == null) continue;
                    Mat4 world = nodeWorldTransforms.containsKey(i) ? nodeWorldTransforms.get(i) : Mat4.identity();
                    JsonValue mesh = meshes.get(meshIdx.asInt());
                    addMeshTriangles(mesh, world, tris);
                }
            } else {
                // no node hierarchy info; just walk every mesh with identity transform
                for (int i = 0; i < meshes.size(); i++) {
                    addMeshTriangles(meshes.get(i), Mat4.identity(), tris);
                }
            }
            return tris;
        }

        private void addMeshTriangles(JsonValue mesh, Mat4 world, List<Triangle> out) {
            JsonValue prims = mesh.get("primitives");
            if (prims == null) return;
            for (int p = 0; p < prims.size(); p++) {
                JsonValue prim = prims.get(p);
                JsonValue mode = prim.get("mode");
                int modeVal = mode == null ? 4 : mode.asInt();
                if (modeVal != 4) continue; // only TRIANGLES supported
                JsonValue attrs = prim.get("attributes");
                if (attrs == null) continue;
                JsonValue posAccessorIdx = attrs.get("POSITION");
                if (posAccessorIdx == null) continue;

                float[][] positions = readVec3Accessor(posAccessorIdx.asInt());
                int[] indices;
                JsonValue idxNode = prim.get("indices");
                if (idxNode != null) {
                    indices = readIndexAccessor(idxNode.asInt());
                } else {
                    indices = new int[positions.length];
                    for (int i = 0; i < indices.length; i++) indices[i] = i;
                }

                for (int i = 0; i + 2 < indices.length; i += 3) {
                    float[] a = world.transformPoint(positions[indices[i]][0], positions[indices[i]][1], positions[indices[i]][2]);
                    float[] b = world.transformPoint(positions[indices[i + 1]][0], positions[indices[i + 1]][1], positions[indices[i + 1]][2]);
                    float[] c = world.transformPoint(positions[indices[i + 2]][0], positions[indices[i + 2]][1], positions[indices[i + 2]][2]);
                    out.add(new Triangle(a, b, c));
                }
            }
        }

        private Map<Integer, Mat4> computeNodeWorldTransforms() {
            Map<Integer, Mat4> result = new HashMap<>();
            JsonValue nodes = json.get("nodes");
            if (nodes == null) return result;

            JsonValue scenesArr = json.get("scenes");
            JsonValue sceneIdxNode = json.get("scene");
            int sceneIdx = sceneIdxNode == null ? 0 : sceneIdxNode.asInt();

            List<Integer> roots = new ArrayList<>();
            if (scenesArr != null && scenesArr.size() > sceneIdx) {
                JsonValue sceneNodes = scenesArr.get(sceneIdx).get("nodes");
                if (sceneNodes != null) {
                    for (int i = 0; i < sceneNodes.size(); i++) roots.add(sceneNodes.get(i).asInt());
                }
            }
            if (roots.isEmpty()) {
                // fall back: treat every node not referenced as a child as a root
                Set<Integer> children = new HashSet<>();
                for (int i = 0; i < nodes.size(); i++) {
                    JsonValue ch = nodes.get(i).get("children");
                    if (ch != null) for (int c = 0; c < ch.size(); c++) children.add(ch.get(c).asInt());
                }
                for (int i = 0; i < nodes.size(); i++) if (!children.contains(i)) roots.add(i);
            }

            for (int root : roots) {
                walkNode(root, Mat4.identity(), nodes, result);
            }
            return result;
        }

        private void walkNode(int idx, Mat4 parentWorld, JsonValue nodes, Map<Integer, Mat4> result) {
            JsonValue node = nodes.get(idx);
            Mat4 local = localTransform(node);
            Mat4 world = parentWorld.multiply(local);
            result.put(idx, world);
            JsonValue children = node.get("children");
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    walkNode(children.get(i).asInt(), world, nodes, result);
                }
            }
        }

        private Mat4 localTransform(JsonValue node) {
            JsonValue matrix = node.get("matrix");
            if (matrix != null) {
                double[] m = new double[16];
                for (int i = 0; i < 16; i++) m[i] = matrix.get(i).asDouble();
                return new Mat4(m);
            }
            double[] t = readVec3OrDefault(node.get("translation"), 0, 0, 0);
            double[] q = readVec4OrDefault(node.get("rotation"), 0, 0, 0, 1);
            double[] s = readVec3OrDefault(node.get("scale"), 1, 1, 1);
            return Mat4.fromTrs(t, q, s);
        }

        private double[] readVec3OrDefault(JsonValue v, double dx, double dy, double dz) {
            if (v == null) return new double[]{dx, dy, dz};
            return new double[]{v.get(0).asDouble(), v.get(1).asDouble(), v.get(2).asDouble()};
        }

        private double[] readVec4OrDefault(JsonValue v, double dx, double dy, double dz, double dw) {
            if (v == null) return new double[]{dx, dy, dz, dw};
            return new double[]{v.get(0).asDouble(), v.get(1).asDouble(), v.get(2).asDouble(), v.get(3).asDouble()};
        }

        // ---- Accessor decoding ----

        private float[][] readVec3Accessor(int accessorIdx) {
            JsonValue accessors = json.get("accessors");
            JsonValue accessor = accessors.get(accessorIdx);
            int count = accessor.get("count").asInt();
            int componentType = accessor.get("componentType").asInt();
            String type = accessor.get("type").asString();
            if (!"VEC3".equals(type)) throw new RuntimeException("POSITION accessor is not VEC3");
            if (componentType != 5126) throw new RuntimeException("POSITION accessor must be FLOAT (5126), got " + componentType);

            BufferViewSlice slice = resolveAccessorBuffer(accessor);
            int stride = slice.stride > 0 ? slice.stride : 12; // 3 floats
            ByteBuffer bb = ByteBuffer.wrap(slice.bytes).order(ByteOrder.LITTLE_ENDIAN);

            float[][] out = new float[count][3];
            for (int i = 0; i < count; i++) {
                int base = i * stride;
                out[i][0] = bb.getFloat(base);
                out[i][1] = bb.getFloat(base + 4);
                out[i][2] = bb.getFloat(base + 8);
            }
            return out;
        }

        private int[] readIndexAccessor(int accessorIdx) {
            JsonValue accessors = json.get("accessors");
            JsonValue accessor = accessors.get(accessorIdx);
            int count = accessor.get("count").asInt();
            int componentType = accessor.get("componentType").asInt();

            BufferViewSlice slice = resolveAccessorBuffer(accessor);
            ByteBuffer bb = ByteBuffer.wrap(slice.bytes).order(ByteOrder.LITTLE_ENDIAN);

            int[] out = new int[count];
            int compSize;
            switch (componentType) {
                case 5121: compSize = 1; break; // UNSIGNED_BYTE
                case 5123: compSize = 2; break; // UNSIGNED_SHORT
                case 5125: compSize = 4; break; // UNSIGNED_INT
                default: throw new RuntimeException("Unsupported index componentType " + componentType);
            }
            int stride = slice.stride > 0 ? slice.stride : compSize;
            for (int i = 0; i < count; i++) {
                int base = i * stride;
                switch (componentType) {
                    case 5121: out[i] = bb.get(base) & 0xFF; break;
                    case 5123: out[i] = bb.getShort(base) & 0xFFFF; break;
                    case 5125: out[i] = bb.getInt(base); break;
                }
            }
            return out;
        }

        /** Resolves the raw byte window (accessor.byteOffset applied) and effective stride for an accessor. */
        private BufferViewSlice resolveAccessorBuffer(JsonValue accessor) {
            JsonValue bufferViews = json.get("bufferViews");
            int bvIdx = accessor.get("bufferView").asInt();
            JsonValue bv = bufferViews.get(bvIdx);
            int bvByteOffset = bv.get("byteOffset") != null ? bv.get("byteOffset").asInt() : 0;
            int bvByteLength = bv.get("byteLength").asInt();
            int bvStride = bv.get("byteStride") != null ? bv.get("byteStride").asInt() : 0;
            int accessorByteOffset = accessor.get("byteOffset") != null ? accessor.get("byteOffset").asInt() : 0;

            // Only GLB-embedded buffer (buffer 0, no uri, backed by BIN chunk) is supported.
            byte[] source = binChunk;
            if (source == null) throw new RuntimeException("No BIN chunk in GLB; external buffers not supported");

            int start = bvByteOffset + accessorByteOffset;
            int len = bvByteLength - accessorByteOffset;
            byte[] window = Arrays.copyOfRange(source, start, start + Math.max(len, 0));
            return new BufferViewSlice(window, bvStride);
        }
    }

    static final class BufferViewSlice {
        final byte[] bytes;
        final int stride;
        BufferViewSlice(byte[] bytes, int stride) { this.bytes = bytes; this.stride = stride; }
    }

    // ---------- Minimal JSON parser (object/array/string/number/boolean/null) ----------

    static final class JsonValue {
        static final int OBJECT = 0, ARRAY = 1, STRING = 2, NUMBER = 3, BOOL = 4, NULL = 5;
        final int type;
        Map<String, JsonValue> obj;
        List<JsonValue> arr;
        String str;
        double num;
        boolean bool;

        private JsonValue(int type) { this.type = type; }

        static JsonValue ofObject(Map<String, JsonValue> m) { JsonValue v = new JsonValue(OBJECT); v.obj = m; return v; }
        static JsonValue ofArray(List<JsonValue> a) { JsonValue v = new JsonValue(ARRAY); v.arr = a; return v; }
        static JsonValue ofString(String s) { JsonValue v = new JsonValue(STRING); v.str = s; return v; }
        static JsonValue ofNumber(double n) { JsonValue v = new JsonValue(NUMBER); v.num = n; return v; }
        static JsonValue ofBool(boolean b) { JsonValue v = new JsonValue(BOOL); v.bool = b; return v; }
        static JsonValue ofNull() { return new JsonValue(NULL); }

        JsonValue get(String key) { return type == OBJECT ? obj.get(key) : null; }
        JsonValue get(int idx) { return type == ARRAY ? arr.get(idx) : null; }
        int size() { return type == ARRAY ? arr.size() : (type == OBJECT ? obj.size() : 0); }
        int asInt() { return (int) num; }
        double asDouble() { return num; }
        String asString() { return str; }
    }

    static final class JsonParser {
        private final String s;
        private int pos = 0;

        JsonParser(String s) { this.s = s; }

        JsonValue parseValue() {
            skipWs();
            char c = s.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return JsonValue.ofString(parseString());
            if (c == 't') { expect("true"); return JsonValue.ofBool(true); }
            if (c == 'f') { expect("false"); return JsonValue.ofBool(false); }
            if (c == 'n') { expect("null"); return JsonValue.ofNull(); }
            return parseNumber();
        }

        private JsonValue parseObject() {
            Map<String, JsonValue> m = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (peek() == '}') { pos++; return JsonValue.ofObject(m); }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expectChar(':');
                JsonValue val = parseValue();
                m.put(key, val);
                skipWs();
                char c = s.charAt(pos++);
                if (c == '}') break;
                if (c != ',') throw new RuntimeException("Expected ',' or '}' at " + pos);
            }
            return JsonValue.ofObject(m);
        }

        private JsonValue parseArray() {
            List<JsonValue> a = new ArrayList<>();
            pos++; // [
            skipWs();
            if (peek() == ']') { pos++; return JsonValue.ofArray(a); }
            while (true) {
                JsonValue v = parseValue();
                a.add(v);
                skipWs();
                char c = s.charAt(pos++);
                if (c == ']') break;
                if (c != ',') throw new RuntimeException("Expected ',' or ']' at " + pos);
                skipWs();
            }
            return JsonValue.ofArray(a);
        }

        private String parseString() {
            expectChar('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(pos++);
                if (c == '"') break;
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            String hex = s.substring(pos, pos + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                            break;
                        default: throw new RuntimeException("Bad escape at " + pos);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private JsonValue parseNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.' ||
                    s.charAt(pos) == 'e' || s.charAt(pos) == 'E' || s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                pos++;
            }
            return JsonValue.ofNumber(Double.parseDouble(s.substring(start, pos)));
        }

        private void expect(String lit) {
            if (!s.startsWith(lit, pos)) throw new RuntimeException("Expected '" + lit + "' at " + pos);
            pos += lit.length();
        }

        private void expectChar(char c) {
            if (s.charAt(pos) != c) throw new RuntimeException("Expected '" + c + "' at " + pos);
            pos++;
        }

        private char peek() { return s.charAt(pos); }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }
    }
}
