package elgatopro300.bbsfbx.model.fbx;

import mchorse.bbs_mod.bobj.BOBJLoader.BOBJMesh;

/**
 * A BOBJMesh with an extra field for the diffuse texture filename resolved
 * from the FBX material — used by FBXModelLoader to pick a default texture
 * when no per-material folder is found.
 */
public class FBXMesh extends BOBJMesh
{
    public String texture;

    public FBXMesh(String name)
    {
        super(name);
    }
}