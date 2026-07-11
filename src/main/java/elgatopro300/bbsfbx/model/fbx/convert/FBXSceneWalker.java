package elgatopro300.bbsfbx.model.fbx.convert;

import org.joml.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.AINode;

import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads the raw Assimp node tree: per-mesh world transforms, which node each
 * mesh belongs to, and each node's parent name. Does no BOBJ construction —
 * see {@link FBXArmatureBuilder} for turning this into bones.
 */
public final class FBXSceneWalker
{
    private FBXSceneWalker() {}

    public static Map<Integer, Matrix4f> collectMeshTransforms(AINode rootNode, Map<Integer, String> meshNodeNames, Map<String, String> nodeParents)
    {
        Map<Integer, Matrix4f> meshTransforms = new HashMap<>();
        collectMeshTransforms(rootNode, new Matrix4f(), meshTransforms, meshNodeNames, nodeParents);
        return meshTransforms;
    }

    private static void collectMeshTransforms(AINode node, Matrix4f parentGlobal, Map<Integer, Matrix4f> meshTransforms, Map<Integer, String> meshNodeNames, Map<String, String> nodeParents)
    {
        Matrix4f local = FBXMath.toMatrix4f(node.mTransformation());
        Matrix4f global = new Matrix4f(parentGlobal).mul(local);

        String nodeName = node.mName().dataString();
        IntBuffer meshIndices = node.mMeshes();
        int numMeshes = node.mNumMeshes();
        if (meshIndices != null)
        {
            for (int i = 0; i < numMeshes; i++)
            {
                int meshIndex = meshIndices.get(i);
                meshTransforms.putIfAbsent(meshIndex, new Matrix4f(global));
                meshNodeNames.putIfAbsent(meshIndex, nodeName);
            }
        }

        PointerBuffer children = node.mChildren();
        int numChildren = node.mNumChildren();
        for (int i = 0; i < numChildren; i++)
        {
            AINode child = AINode.create(children.get(i));
            String childName = child.mName().dataString();
            // Skip synthetic roots so top-level objects stay parentless.
            String parentForChild = (nodeName.equals("RootNode") || nodeName.equals("Armature")) ? "" : nodeName;
            nodeParents.putIfAbsent(childName, parentForChild);
            collectMeshTransforms(child, global, meshTransforms, meshNodeNames, nodeParents);
        }
    }

    /**
     * Collects every node's local (relative-to-parent) transform, keyed by
     * node name. Used by {@link FBXAnimationBaker} as a rest-pose fallback
     * for animated nodes that aren't skinned bones.
     */
    public static void collectNodeLocals(AINode node, Map<String, Matrix4f> map)
    {
        map.put(node.mName().dataString(), FBXMath.toMatrix4f(node.mTransformation()));

        int numChildren = node.mNumChildren();
        PointerBuffer children = node.mChildren();

        for (int i = 0; i < numChildren; i++)
        {
            collectNodeLocals(AINode.create(children.get(i)), map);
        }
    }
}