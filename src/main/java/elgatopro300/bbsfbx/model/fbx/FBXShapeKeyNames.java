package elgatopro300.bbsfbx.model.fbx;

import org.lwjgl.assimp.AIAnimMesh;

public class FBXShapeKeyNames
{
    public static String buildShapeKeyName(AIAnimMesh animMesh, String meshName, int animIndex)
    {
        String name = safeName(animMesh.mName().dataString());

        if (!name.isBlank())
        {
            return normalizeShapeKeyName(name);
        }

        if (!meshName.isBlank())
        {
            return normalizeShapeKeyName(meshName + "_ShapeKey_" + animIndex);
        }

        return "ShapeKey_" + animIndex;
    }

    public static String safeName(String name)
    {
        return name == null ? "" : name.trim();
    }

    private static String normalizeShapeKeyName(String name)
    {
        return stripRepeatedName(safeName(name));
    }

    private static String stripRepeatedName(String name)
    {
        if (name == null)
        {
            return "";
        }

        name = name.trim();

        if (name.isEmpty())
        {
            return "";
        }

        int dot = name.lastIndexOf('.');

        if (dot > 0 && dot < name.length() - 1)
        {
            String left = name.substring(0, dot).trim();
            String right = name.substring(dot + 1).trim();

            if (left.equals(right))
            {
                return left;
            }
        }

        return name;
    }
}