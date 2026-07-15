package elgatopro300.bbsfbx.mixin;

import elgatopro300.bbsfbx.model.fbx.loaders.FBXCompiledData;
import elgatopro300.bbsfbx.model.fbx.loaders.IShapeKeyHolder;
import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJLoader;
import mchorse.bbs_mod.cubic.render.vao.BOBJModelVAO;
import mchorse.bbs_mod.obj.shapes.ShapeKeys;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BOBJModelVAO.class, remap = false)
public abstract class BOBJModelVAOMixin implements IShapeKeyHolder
{
    @Shadow public BOBJLoader.CompiledData data;
    @Shadow public BOBJArmature armature;
    @Shadow private int count;
    @Shadow public int vertexBuffer;
    @Shadow public int normalBuffer;
    @Shadow public int lightBuffer;
    @Shadow public int tangentBuffer;
    @Shadow private float[] tmpVertices;
    @Shadow private float[] tmpNormals;
    @Shadow private int[] tmpLight;
    @Shadow private float[] tmpTangents;

    @Shadow protected abstract void processData(float[] newVertices, float[] newNormals);

    private ShapeKeys bbsFbx$shapeKeys;

    @Override
    public void bbsFbx$setShapeKeys(ShapeKeys shapeKeys)
    {
        this.bbsFbx$shapeKeys = shapeKeys;
    }

    @Inject(method = "updateMesh", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$updateMeshWithShapeKeys(StencilMap stencilMap, CallbackInfo info)
    {
        if (!(this.data instanceof FBXCompiledData fbxData))
        {
            return;
        }

        info.cancel();

        org.joml.Vector4f sum = new org.joml.Vector4f();
        org.joml.Vector4f result = new org.joml.Vector4f(0F, 0F, 0F, 0F);
        org.joml.Vector3f sumNormal = new org.joml.Vector3f();
        org.joml.Vector3f resultNormal = new org.joml.Vector3f();

        float[] oldVertices = this.data.posData;
        float[] oldNormals = this.data.normData;

        float[] morphedVertices = oldVertices;
        float[] morphedNormals = oldNormals;

        if (this.bbsFbx$shapeKeys != null && !this.bbsFbx$shapeKeys.shapeKeys.isEmpty())
        {
            morphedVertices = new float[oldVertices.length];
            System.arraycopy(oldVertices, 0, morphedVertices, 0, oldVertices.length);

            morphedNormals = new float[oldNormals.length];
            System.arraycopy(oldNormals, 0, morphedNormals, 0, oldNormals.length);

            for (java.util.Map.Entry<String, Float> entry : this.bbsFbx$shapeKeys.shapeKeys.entrySet())
            {
                String key = entry.getKey();
                float weight = entry.getValue();

                if (weight == 0F)
                {
                    continue;
                }

                float[] shapeVerts = fbxData.shapeKeyVertices.get(key);
                float[] shapeNorms = fbxData.shapeKeyNormals.get(key);

                if (shapeVerts != null)
                {
                    for (int i = 0; i < morphedVertices.length; i++)
                    {
                        morphedVertices[i] += weight * (shapeVerts[i] - oldVertices[i]);
                    }
                }

                if (shapeNorms != null)
                {
                    for (int i = 0; i < morphedNormals.length; i++)
                    {
                        morphedNormals[i] += weight * (shapeNorms[i] - oldNormals[i]);
                    }
                }
            }
        }

        float[] newVertices = this.tmpVertices;
        float[] newNormals = this.tmpNormals;

        org.joml.Matrix4f[] matrices = this.armature.matrices;

        for (int i = 0, c = this.count; i < c; i++)
        {
            int count = 0;
            float maxWeight = -1;
            int lightBone = -1;

            for (int w = 0; w < 4; w++)
            {
                float weight = this.data.weightData[i * 4 + w];

                if (weight > 0)
                {
                    int index = this.data.boneIndexData[i * 4 + w];

                    sum.set(morphedVertices[i * 3], morphedVertices[i * 3 + 1], morphedVertices[i * 3 + 2], 1F);
                    matrices[index].transform(sum);
                    result.add(sum.mul(weight));

                    sumNormal.set(morphedNormals[i * 3], morphedNormals[i * 3 + 1], morphedNormals[i * 3 + 2]);
                    mchorse.bbs_mod.utils.joml.Matrices.TEMP_3F.set(matrices[index]).transform(sumNormal);
                    resultNormal.add(sumNormal.mul(weight));

                    count++;

                    if (weight > maxWeight)
                    {
                        lightBone = index;
                        maxWeight = weight;
                    }
                }
            }

            if (count == 0)
            {
                result.set(morphedVertices[i * 3], morphedVertices[i * 3 + 1], morphedVertices[i * 3 + 2], 1F);
                resultNormal.set(morphedNormals[i * 3], morphedNormals[i * 3 + 1], morphedNormals[i * 3 + 2]);
            }

            result.x /= result.w;
            result.y /= result.w;
            result.z /= result.w;

            newVertices[i * 3] = result.x;
            newVertices[i * 3 + 1] = result.y;
            newVertices[i * 3 + 2] = result.z;

            newNormals[i * 3] = resultNormal.x;
            newNormals[i * 3 + 1] = resultNormal.y;
            newNormals[i * 3 + 2] = resultNormal.z;

            result.set(0F, 0F, 0F, 0F);
            resultNormal.set(0F, 0F, 0F);

            if (stencilMap != null)
            {
                this.tmpLight[i * 2] = Math.max(0, stencilMap.increment ? lightBone : 0);
                this.tmpLight[i * 2 + 1] = 0;
            }
        }

        this.processData(newVertices, newNormals);

        org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, this.vertexBuffer);
        org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, newVertices, org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW);

        org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, this.normalBuffer);
        org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, newNormals, org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW);

        if (mchorse.bbs_mod.client.BBSRendering.isIrisShadersEnabled())
        {
            mchorse.bbs_mod.client.BBSRendering.calculateTangents(this.tmpTangents, newVertices, newNormals, this.data.texData);

            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, this.tangentBuffer);
            org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, this.tmpTangents, org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW);
        }

        if (stencilMap != null)
        {
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, this.lightBuffer);
            org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, this.tmpLight, org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW);
        }
    }
}
