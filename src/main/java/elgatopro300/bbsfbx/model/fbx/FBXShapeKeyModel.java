package elgatopro300.bbsfbx.model.fbx;

import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJLoader;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class FBXShapeKeyModel extends BOBJModel
{
    private final Set<String> shapeKeys;

    public FBXShapeKeyModel(BOBJArmature armature, List<BOBJLoader.CompiledData> meshes, boolean simple, Set<String> shapeKeys)
    {
        super(armature, meshes, simple);

        LinkedHashSet<String> copy = new LinkedHashSet<>();

        if (shapeKeys != null)
        {
            copy.addAll(shapeKeys);
        }

        this.shapeKeys = Collections.unmodifiableSet(copy);
    }

    @Override
    public Set<String> getShapeKeys()
    {
        return this.shapeKeys;
    }
}