package elgatopro300.bbsfbx.model.fbx.loaders;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJMesh;
import mchorse.bbs_mod.bobj.BOBJLoader.CompiledData;
import java.util.Map;
public class FBXCompiledData extends CompiledData
{
    public final Map<String, float[]> shapeKeyVertices;
    public final Map<String, float[]> shapeKeyNormals;
    public FBXCompiledData(
            float[] posData, float[] texData, float[] normData,
            float[] weightData, int[] boneIndexData, int[] indexData,
            BOBJMesh mesh,
            Map<String, float[]> shapeKeyVertices,
            Map<String, float[]> shapeKeyNormals)
    {
        super(posData, texData, normData, weightData, boneIndexData, indexData, mesh);
        this.shapeKeyVertices = shapeKeyVertices;
        this.shapeKeyNormals = shapeKeyNormals;
    }
}