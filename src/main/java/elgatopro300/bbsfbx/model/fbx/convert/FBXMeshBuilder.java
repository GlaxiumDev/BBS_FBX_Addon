package elgatopro300.bbsfbx.model.fbx.convert;

import elgatopro300.bbsfbx.model.fbx.FBXMesh;

import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJMesh;
import mchorse.bbs_mod.bobj.BOBJLoader.Face;
import mchorse.bbs_mod.bobj.BOBJLoader.IndexGroup;
import mchorse.bbs_mod.bobj.BOBJLoader.Vertex;
import mchorse.bbs_mod.bobj.BOBJLoader.Weight;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2d;
import org.joml.Vector3f;

import org.lwjgl.assimp.AIBone;
import org.lwjgl.assimp.AIMaterial;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIString;
import org.lwjgl.assimp.AIVector3D;
import org.lwjgl.assimp.AIVertexWeight;
import org.lwjgl.assimp.Assimp;

import java.nio.IntBuffer;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Converts a single Assimp mesh into a {@link BOBJMesh} (as an
 * {@link FBXMesh}) — vertices, normals, UVs, faces, bone weights, and the
 * diffuse material/texture name.
 */
public final class FBXMeshBuilder
{
    private FBXMeshBuilder() {}

    /**
     * For non-skinned meshes the full node transform (including translation)
     * is applied, and every vertex is weighted to the object's own bone
     * ({@code objectBoneName}) so it pivots at its Blender origin.
     */
    public static void buildMesh(AIScene scene, AIMesh aiMesh, int meshIndex, List<Vertex> vertices, List<Vector2d> textures, List<Vector3f> normals, List<BOBJMesh> meshes, BOBJArmature armature, float scaleFactor, Matrix4f rootCorrection, float offsetX, float offsetY, float offsetZ, Map<Integer, Matrix4f> meshTransforms, String objectBoneName)
    {
        FBXMesh mesh = new FBXMesh(aiMesh.mName().dataString());
        mesh.armatureName = armature.name;
        mesh.armature = armature;

        Matrix4f meshTransform = meshTransforms.get(meshIndex);
        boolean skinned = aiMesh.mNumBones() > 0;
        boolean applyNodeTransform = !skinned && meshTransform != null;
        Matrix4f meshRotationOnly = null;
        if (skinned && meshTransform != null)
        {
            Quaternionf rot = new Quaternionf();
            meshTransform.getUnnormalizedRotation(rot);
            meshRotationOnly = new Matrix4f().rotation(rot);
        }

        int vertexBaseIndex = vertices.size();
        int textureBaseIndex = textures.size();
        int normalBaseIndex = normals.size();

        Vector3f pos = new Vector3f();

        AIVector3D.Buffer aiVertices = aiMesh.mVertices();
        while (aiVertices.remaining() > 0)
        {
            AIVector3D aiVertex = aiVertices.get();

            pos.set(aiVertex.x(), aiVertex.y(), aiVertex.z());
            if (applyNodeTransform)
            {
                meshTransform.transformPosition(pos);
            }
            else if (meshRotationOnly != null)
            {
                meshRotationOnly.transformPosition(pos);
            }

            pos.mul(scaleFactor);
            rootCorrection.transformPosition(pos);

            pos.x += offsetX;
            pos.y += offsetY;
            pos.z += offsetZ;

            vertices.add(new Vertex(pos.x, pos.y, pos.z));
        }

        AIVector3D.Buffer aiNormals = aiMesh.mNormals();
        if (aiNormals != null)
        {
            while (aiNormals.remaining() > 0)
            {
                AIVector3D aiNormal = aiNormals.get();
                Vector3f norm = new Vector3f(aiNormal.x(), aiNormal.y(), aiNormal.z());
                if (applyNodeTransform)
                {
                    meshTransform.transformDirection(norm);
                }
                else if (meshRotationOnly != null)
                {
                    meshRotationOnly.transformDirection(norm);
                }

                rootCorrection.transformDirection(norm);
                norm.normalize();

                normals.add(norm);
            }
        }
        else
        {
            int count = aiMesh.mNumVertices();
            for (int i = 0; i < count; i++)
            {
                normals.add(new Vector3f(0, 1, 0));
            }
        }

        AIVector3D.Buffer aiTextureCoords = aiMesh.mTextureCoords(0);
        if (aiTextureCoords != null)
        {
            while (aiTextureCoords.remaining() > 0)
            {
                AIVector3D aiTexCoord = aiTextureCoords.get();
                textures.add(new Vector2d(aiTexCoord.x(), aiTexCoord.y()));
            }
        }
        else
        {
            int count = aiMesh.mNumVertices();
            for (int i = 0; i < count; i++)
            {
                textures.add(new Vector2d(0, 0));
            }
        }

        int numFaces = aiMesh.mNumFaces();
        for (int i = 0; i < numFaces; i++)
        {
            IntBuffer faceIndices = aiMesh.mFaces().get(i).mIndices();
            if (faceIndices.remaining() == 3)
            {
                Face face = new Face();
                for (int j = 0; j < 3; j++)
                {
                    int index = faceIndices.get(j);
                    IndexGroup group = new IndexGroup();
                    group.idxPos = vertexBaseIndex + index;
                    group.idxVecNormal = normalBaseIndex + index;
                    group.idxTextCoord = textureBaseIndex + index;
                    face.idxGroups[j] = group;
                }
                mesh.faces.add(face);
            }
        }

        if (skinned)
        {
            int numBones = aiMesh.mNumBones();
            for (int i = 0; i < numBones; i++)
            {
                AIBone aiBone = AIBone.create(Objects.requireNonNull(aiMesh.mBones()).get(i));
                String boneName = aiBone.mName().dataString();

                AIVertexWeight.Buffer aiWeights = aiBone.mWeights();
                while (aiWeights.remaining() > 0)
                {
                    AIVertexWeight aiWeight = aiWeights.get();
                    int vertexId = aiWeight.mVertexId();
                    float weight = aiWeight.mWeight();

                    if (vertexId + vertexBaseIndex < vertices.size())
                    {
                        vertices.get(vertexBaseIndex + vertexId).weights.add(new Weight(boneName, weight));
                    }
                }
            }
        }
        else if (objectBoneName != null)
        {
            for (int v = vertexBaseIndex; v < vertices.size(); v++)
            {
                vertices.get(v).weights.add(new Weight(objectBoneName, 1.0f));
            }
        }

        int materialIndex = aiMesh.mMaterialIndex();
        if (materialIndex >= 0 && materialIndex < scene.mNumMaterials())
        {
            AIMaterial material = AIMaterial.create(Objects.requireNonNull(scene.mMaterials()).get(materialIndex));

            AIString nameStr = AIString.calloc();
            if (Assimp.aiGetMaterialString(material, Assimp.AI_MATKEY_NAME, 0, 0, nameStr) == Assimp.aiReturn_SUCCESS)
            {
                String materialName = nameStr.dataString();
                if (!materialName.isEmpty())
                {
                    mesh.name = materialName;
                }
            }
            nameStr.free();

            AIString path = AIString.calloc();

            if (Assimp.aiGetMaterialTexture(material, Assimp.aiTextureType_DIFFUSE, 0, path, (IntBuffer) null, null, null, null, null, null) == Assimp.aiReturn_SUCCESS)
            {
                String texturePath = path.dataString();

                if (!texturePath.isEmpty())
                {
                    texturePath = texturePath.replace('\\', '/');
                    int lastSlash = texturePath.lastIndexOf('/');

                    if (lastSlash >= 0)
                    {
                        texturePath = texturePath.substring(lastSlash + 1);
                    }

                    mesh.texture = texturePath;
                }
            }

            path.free();
        }

        meshes.add(mesh);
    }

    /**
     * Ensures every vertex ends up with at least one bone weight (falling
     * back to the armature's first bone), eliminates near-zero weights, and
     * renormalizes each vertex's weights to sum to 1.
     */
    public static void finalizeWeights(List<Vertex> vertices, BOBJArmature globalArmature)
    {
        for (Vertex vertex : vertices)
        {
            if (vertex.weights.isEmpty())
            {
                if (!globalArmature.orderedBones.isEmpty())
                {
                    vertex.weights.add(new Weight(globalArmature.orderedBones.get(0).name, 1.0f));
                }
            }
            else
            {
                vertex.eliminateTinyWeights();

                float sum = 0;
                for (Weight w : vertex.weights)
                {
                    sum += w.factor;
                }

                if (sum > 0 && Math.abs(sum - 1.0f) > 0.001f)
                {
                    for (Weight w : vertex.weights)
                    {
                        w.factor /= sum;
                    }
                }
            }
        }
    }
}