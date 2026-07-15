package elgatopro300.bbsfbx.model.fbx.loaders;

import elgatopro300.bbsfbx.model.fbx.FBXMesh;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.bobj.BOBJLoader;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJData;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJMesh;
import mchorse.bbs_mod.bobj.BOBJLoader.CompiledData;

import org.joml.Vector2d;
import org.joml.Vector3f;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/**
 * Flattens a single BOBJMesh (plus the shared vertex/texture/normal pools in
 * BOBJData) into the packed float/int arrays BBS FS's renderer expects, as a
 * CompiledData.
 */
public final class FBXMeshCompiler
{
    private FBXMeshCompiler() {}

    public static CompiledData compile(BOBJData data, BOBJMesh mesh)
    {
        int totalVertices = 0;
        for (BOBJLoader.Face face : mesh.faces)
        {
            totalVertices += face.idxGroups.length;
        }

        float[] pos = new float[totalVertices * 3];
        float[] tex = new float[totalVertices * 2];
        float[] norm = new float[totalVertices * 3];
        float[] weights = new float[totalVertices * 4];
        int[] bones = new int[totalVertices * 4];
        int[] indices = new int[totalVertices];

        int vIndex = 0;  // >> Vertex index */
        int wIndex = 0;  // >> Weight/Bone index (x4) */
        int pIndex = 0;  // >> Position/Normal index (x3) */
        int tIndex = 0;  // >> Texture index (x2) */

        for (BOBJLoader.Face face : mesh.faces)
        {
            for (BOBJLoader.IndexGroup group : face.idxGroups)
            {
                BOBJLoader.Vertex v = data.vertices.get(group.idxPos);
                Vector2d t = data.textures.get(group.idxTextCoord);
                Vector3f n = data.normals.get(group.idxVecNormal);

                pos[pIndex] = v.x; pos[pIndex+1] = v.y; pos[pIndex+2] = v.z;
                norm[pIndex] = n.x; norm[pIndex+1] = n.y; norm[pIndex+2] = n.z;
                pIndex += 3;

                tex[tIndex] = (float) t.x; tex[tIndex+1] = (float) t.y;
                tIndex += 2;

                if (v.weights.isEmpty())
                {
                    weights[wIndex] = 1.0f; bones[wIndex] = 0;
                    weights[wIndex+1] = 0.0f; bones[wIndex+1] = -1;
                    weights[wIndex+2] = 0.0f; bones[wIndex+2] = -1;
                    weights[wIndex+3] = 0.0f; bones[wIndex+3] = -1;
                } else
                {
                    for (int i = 0; i < 4; i++)
                    {
                        if (i < v.weights.size())
                        {
                            BOBJLoader.Weight w = v.weights.get(i);
                            weights[wIndex+i] = w.factor;
                            BOBJBone bone = mesh.armature != null ? mesh.armature.bones.get(w.name) : null;
                            bones[wIndex+i] = (bone == null ? 0 : bone.index);
                        } else
                        {
                            weights[wIndex+i] = 0f;
                            bones[wIndex+i] = -1;
                        }
                    }
                }
                wIndex += 4;
                indices[vIndex] = vIndex;
                vIndex++;
            }
        }

        Map<String, float[]> shapeKeyVerticesCompiled = new HashMap<>();
        Map<String, float[]> shapeKeyNormalsCompiled = new HashMap<>();

        if (mesh instanceof FBXMesh fbxMesh && fbxMesh.shapeKeyVertices != null)
        {
            int vertexBaseIndex = fbxMesh.vertexBaseIndex;
            int normalBaseIndex = fbxMesh.normalBaseIndex;

            for (String key : fbxMesh.shapeKeyVertices.keySet())
            {
                shapeKeyVerticesCompiled.put(key, new float[totalVertices * 3]);
                shapeKeyNormalsCompiled.put(key, new float[totalVertices * 3]);
            }

            int pIdx = 0;
            for (BOBJLoader.Face face : mesh.faces)
            {
                for (BOBJLoader.IndexGroup group : face.idxGroups)
                {
                    int localVertIndex = group.idxPos - vertexBaseIndex;
                    int localNormalIndex = group.idxVecNormal - normalBaseIndex;

                    for (String key : fbxMesh.shapeKeyVertices.keySet())
                    {
                        List<Vector3f> shapeVerts = fbxMesh.shapeKeyVertices.get(key);
                        List<Vector3f> shapeNorms = fbxMesh.shapeKeyNormals.get(key);

                        float[] sPos = shapeKeyVerticesCompiled.get(key);
                        float[] sNorm = shapeKeyNormalsCompiled.get(key);

                        if (localVertIndex >= 0 && localVertIndex < shapeVerts.size())
                        {
                            Vector3f sv = shapeVerts.get(localVertIndex);
                            sPos[pIdx] = sv.x;
                            sPos[pIdx + 1] = sv.y;
                            sPos[pIdx + 2] = sv.z;
                        }
                        else
                        {
                            sPos[pIdx] = pos[pIdx];
                            sPos[pIdx + 1] = pos[pIdx + 1];
                            sPos[pIdx + 2] = pos[pIdx + 2];
                        }

                        if (localNormalIndex >= 0 && localNormalIndex < shapeNorms.size())
                        {
                            Vector3f sn = shapeNorms.get(localNormalIndex);
                            sNorm[pIdx] = sn.x;
                            sNorm[pIdx + 1] = sn.y;
                            sNorm[pIdx + 2] = sn.z;
                        }
                        else
                        {
                            sNorm[pIdx] = norm[pIdx];
                            sNorm[pIdx + 1] = norm[pIdx + 1];
                            sNorm[pIdx + 2] = norm[pIdx + 2];
                        }
                    }
                    pIdx += 3;
                }
            }
        }

        return new FBXCompiledData(pos, tex, norm, weights, bones, indices, mesh, shapeKeyVerticesCompiled, shapeKeyNormalsCompiled);
    }
}