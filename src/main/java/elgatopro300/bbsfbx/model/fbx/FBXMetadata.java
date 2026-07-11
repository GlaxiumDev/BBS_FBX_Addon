package elgatopro300.bbsfbx.model.fbx;

import org.lwjgl.assimp.AIMetaData;
import org.lwjgl.assimp.AIMetaDataEntry;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.Assimp;

public class FBXMetadata
{
    public int upAxis = 1; /* Default to Y-up */
    public int originalUpAxis = 1;
    public int frontAxis = 2; /* Default to Z-front */
    public int coordAxis = 0; /* Default to X-coord */
    public double unitScaleFactor = 1.0;

    public FBXMetadata(AIScene scene)
    {
        AIMetaData metadata = scene.mMetaData();

        if (metadata == null)
        {
            return;
        }

        for (int i = 0; i < metadata.mNumProperties(); i++)
        {
            String key = metadata.mKeys().get(i).dataString();
            AIMetaDataEntry entry = metadata.mValues().get(i);

            switch (key)
            {
                case "UpAxis" -> this.upAxis = getInt(entry);
                case "OriginalUpAxis" -> this.originalUpAxis = getInt(entry);
                case "FrontAxis" -> this.frontAxis = getInt(entry);
                case "CoordAxis" -> this.coordAxis = getInt(entry);
                case "UnitScaleFactor" -> this.unitScaleFactor = getDouble(entry);
                default -> { /* ignored */ }
            }
        }
    }

    private int getInt(AIMetaDataEntry entry)
    {
        if (entry.mType() == Assimp.AI_INT32)
        {
            return entry.mData(4).asIntBuffer().get(0);
        }
        else if (entry.mType() == Assimp.AI_DOUBLE)
        {
            return (int) entry.mData(8).asDoubleBuffer().get(0);
        }
        return 0;
    }

    private double getDouble(AIMetaDataEntry entry)
    {
        if (entry.mType() == Assimp.AI_DOUBLE)
        {
            return entry.mData(8).asDoubleBuffer().get(0);
        }
        else if (entry.mType() == Assimp.AI_INT32)
        {
            return entry.mData(4).asIntBuffer().get(0);
        }
        return 0;
    }
}