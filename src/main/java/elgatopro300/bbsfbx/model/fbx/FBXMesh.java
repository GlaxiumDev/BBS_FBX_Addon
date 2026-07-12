package elgatopro300.bbsfbx.model.fbx;

import mchorse.bbs_mod.bobj.BOBJLoader.BOBJMesh;

public class FBXMesh extends BOBJMesh
{
    public String texture;

    /** Diffuse/base color from the FBX material when it has NO image texture.
     *  Used to build a synthetic solid-color texture Link instead of writing a
     *  PNG. Stays null when the material had a real texture. */
    public float[] color;

    public FBXMesh(String name)
    {
        super(name);
    }
}