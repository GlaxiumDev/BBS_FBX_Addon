package elgatopro300.bbsfbx.model.fbx.loaders;

import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.bobj.BOBJLoader;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJData;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJMesh;
import mchorse.bbs_mod.bobj.BOBJLoader.CompiledData;

import org.joml.Vector2d;
import org.joml.Vector3f;

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

        return new CompiledData(
                pos, tex, norm, weights, bones, indices,
                mesh
        );
    }
}