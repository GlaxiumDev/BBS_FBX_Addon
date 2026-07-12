package elgatopro300.bbsfbx.model.fbx.convert;

import mchorse.bbs_mod.bobj.BOBJAction;
import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.bobj.BOBJChannel;
import mchorse.bbs_mod.bobj.BOBJGroup;
import mchorse.bbs_mod.bobj.BOBJKeyframe;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import org.lwjgl.assimp.AIAnimation;
import org.lwjgl.assimp.AIBone;
import org.lwjgl.assimp.AINodeAnim;
import org.lwjgl.assimp.AIQuatKey;
import org.lwjgl.assimp.AIQuaternion;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIVector3D;
import org.lwjgl.assimp.AIVectorKey;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Bakes every Assimp animation clip in the scene into a {@link BOBJAction},
 * mirroring the channel layout BOBJ models use (location/rotation/scale per
 * bone group).
 *
 * <p>BBS applies a bone's animation Transform as a delta on top of its rest
 * pose (relBoneMat * T * Rz * Ry * Rx * S), so the values baked here are the
 * node's animated LOCAL transform expressed relative to its rest local
 * transform: delta = restLocal^-1 * animatedLocal. Rotation is emitted as
 * ZYX euler angles in radians, matching the rotateZ/rotateY/rotateX order in
 * BOBJBone#applyTransformations.
 */
public final class FBXAnimationBaker
{
    /** Playback rate BBS animations run at (frames per second). */
    private static final int FRAMES_PER_SECOND = 20;

    private FBXAnimationBaker() {}

    /**
     * Each skinned bone's bind-pose LOCAL transform (parent-relative), from
     * its inverse-bind (offset) matrix — the same source the renderer uses
     * to build relBoneMat. Diffing against these (instead of the raw node
     * transform, which Blender may bake to frame 0) keeps a held pose
     * consistent with the bind pose.
     */
    public static Map<String, Matrix4f> computeBindLocals(Map<String, AIBone> skinnedBones, BOBJArmature armature)
    {
        Map<String, Matrix4f> worldBind = new HashMap<>();
        for (Map.Entry<String, AIBone> entry : skinnedBones.entrySet())
        {
            Matrix4f offset = FBXMath.toMatrix4f(entry.getValue().mOffsetMatrix());
            worldBind.put(entry.getKey(), offset.invert());
        }

        Map<String, Matrix4f> bindLocals = new HashMap<>();
        for (BOBJBone bone : armature.orderedBones)
        {
            Matrix4f world = worldBind.get(bone.name);
            if (world == null)
            {
                continue;
            }

            Matrix4f parentWorld = bone.parent.isEmpty() ? null : worldBind.get(bone.parent);
            Matrix4f local = parentWorld == null
                    ? new Matrix4f(world)
                    : new Matrix4f(parentWorld).invert().mul(world);

            bindLocals.put(bone.name, local);
        }

        return bindLocals;
    }

    public static void processAnimations(AIScene scene, Map<String, BOBJAction> actions, BOBJArmature armature, Map<String, Matrix4f> nodeLocals, Map<String, Matrix4f> bindLocals, float globalScale)
    {
        int numAnimations = scene.mNumAnimations();

        for (int a = 0; a < numAnimations; a++)
        {
            AIAnimation aiAnimation = AIAnimation.create(scene.mAnimations().get(a));

            String name = aiAnimation.mName().dataString();
            if (name.isEmpty())
            {
                name = "animation_" + a;
            }
            else
            {
                // Blender exports clips as "Armature|Walk"; keep only the clip name.
                int bar = name.lastIndexOf('|');
                if (bar >= 0 && bar < name.length() - 1)
                {
                    name = name.substring(bar + 1);
                }
            }

            double ticksPerSecond = aiAnimation.mTicksPerSecond();
            if (ticksPerSecond == 0)
            {
                ticksPerSecond = 24.0;
            }

            BOBJAction action = new BOBJAction(name);

            int numChannels = aiAnimation.mNumChannels();
            for (int c = 0; c < numChannels; c++)
            {
                AINodeAnim nodeAnim = AINodeAnim.create(aiAnimation.mChannels().get(c));
                String nodeName = nodeAnim.mNodeName().dataString();

                /* Only animate nodes that ended up as bones in the armature. */
                if (!armature.bones.containsKey(nodeName))
                {
                    continue;
                }

                /* Prefer the offset-matrix bind local; fall back to the raw
                 * node transform for bones that are animated but not skinned. */
                Matrix4f rest = bindLocals.get(nodeName);
                if (rest == null)
                {
                    rest = nodeLocals.get(nodeName);
                }
                if (rest == null)
                {
                    rest = new Matrix4f();
                }

                processNodeAnimation(nodeAnim, nodeName, rest, action, ticksPerSecond, globalScale);
            }

            if (!action.groups.isEmpty())
            {
                actions.put(name, action);
            }
        }
    }

    private static void processNodeAnimation(AINodeAnim nodeAnim, String nodeName, Matrix4f rest, BOBJAction action, double ticksPerSecond, float globalScale)
    {
        int numPos = nodeAnim.mNumPositionKeys();
        int numRot = nodeAnim.mNumRotationKeys();
        int numScale = nodeAnim.mNumScalingKeys();

        double[] posTimes = new double[numPos];
        Vector3f[] posVals = new Vector3f[numPos];
        AIVectorKey.Buffer posKeys = nodeAnim.mPositionKeys();
        for (int i = 0; i < numPos; i++)
        {
            AIVectorKey key = posKeys.get(i);
            posTimes[i] = key.mTime();
            AIVector3D v = key.mValue();
            posVals[i] = new Vector3f(v.x(), v.y(), v.z());
        }

        double[] rotTimes = new double[numRot];
        Quaternionf[] rotVals = new Quaternionf[numRot];
        AIQuatKey.Buffer rotKeys = nodeAnim.mRotationKeys();
        for (int i = 0; i < numRot; i++)
        {
            AIQuatKey key = rotKeys.get(i);
            rotTimes[i] = key.mTime();
            AIQuaternion q = key.mValue();
            rotVals[i] = new Quaternionf(q.x(), q.y(), q.z(), q.w());
        }

        double[] scaleTimes = new double[numScale];
        Vector3f[] scaleVals = new Vector3f[numScale];
        AIVectorKey.Buffer scaleKeys = nodeAnim.mScalingKeys();
        for (int i = 0; i < numScale; i++)
        {
            AIVectorKey key = scaleKeys.get(i);
            scaleTimes[i] = key.mTime();
            AIVector3D v = key.mValue();
            scaleVals[i] = new Vector3f(v.x(), v.y(), v.z());
        }

        Vector3f restT = new Vector3f();
        rest.getTranslation(restT);
        Vector3f restS = new Vector3f();
        rest.getScale(restS);
        Quaternionf restR = new Quaternionf();
        rest.getUnnormalizedRotation(restR);
        restR.normalize();

        TreeSet<Double> timeSet = new TreeSet<>();
        for (double t : posTimes) timeSet.add(t);
        for (double t : rotTimes) timeSet.add(t);
        for (double t : scaleTimes) timeSet.add(t);

        if (timeSet.isEmpty())
        {
            return;
        }

        BOBJGroup group = new BOBJGroup(nodeName);

        BOBJChannel tx = new BOBJChannel("location.x", 0);
        BOBJChannel ty = new BOBJChannel("location.y", 1);
        BOBJChannel tz = new BOBJChannel("location.z", 2);

        BOBJChannel rx = new BOBJChannel("rotation.x", 3);
        BOBJChannel ry = new BOBJChannel("rotation.y", 4);
        BOBJChannel rz = new BOBJChannel("rotation.z", 5);

        BOBJChannel sx = new BOBJChannel("scale.x", 6);
        BOBJChannel sy = new BOBJChannel("scale.y", 7);
        BOBJChannel sz = new BOBJChannel("scale.z", 8);

        Matrix4f restInv = new Matrix4f(rest).invert();
        Matrix4f animLocal = new Matrix4f();
        Matrix4f delta = new Matrix4f();
        Vector3f dt = new Vector3f();
        Vector3f ds = new Vector3f();
        Quaternionf dq = new Quaternionf();
        Vector3f euler = new Vector3f();

        boolean scaleVaries = false;

        for (double time : timeSet)
        {
            Vector3f t = (numPos > 0) ? interpolateVector(posTimes, posVals, time) : new Vector3f(restT);
            Quaternionf r = (numRot > 0) ? interpolateQuat(rotTimes, rotVals, time) : new Quaternionf(restR);
            Vector3f s = (numScale > 0) ? interpolateVector(scaleTimes, scaleVals, time) : new Vector3f(restS);

            animLocal.translationRotateScale(t.x, t.y, t.z, r.x, r.y, r.z, r.w, s.x, s.y, s.z);
            restInv.mul(animLocal, delta);

            delta.getTranslation(dt);
            delta.getScale(ds);
            delta.getUnnormalizedRotation(dq);
            dq.normalize();
            quatToEulerZYX(dq, euler);

            float frame = (float) (time / ticksPerSecond * FRAMES_PER_SECOND);

            tx.keyframes.add(new BOBJKeyframe(frame, dt.x * globalScale));
            ty.keyframes.add(new BOBJKeyframe(frame, dt.y * globalScale));
            tz.keyframes.add(new BOBJKeyframe(frame, dt.z * globalScale));

            rx.keyframes.add(new BOBJKeyframe(frame, euler.x));
            ry.keyframes.add(new BOBJKeyframe(frame, euler.y));
            rz.keyframes.add(new BOBJKeyframe(frame, euler.z));

            sx.keyframes.add(new BOBJKeyframe(frame, ds.x));
            sy.keyframes.add(new BOBJKeyframe(frame, ds.y));
            sz.keyframes.add(new BOBJKeyframe(frame, ds.z));

            if (Math.abs(ds.x - 1f) > 1e-4f || Math.abs(ds.y - 1f) > 1e-4f || Math.abs(ds.z - 1f) > 1e-4f)
            {
                scaleVaries = true;
            }
        }

        group.channels.add(tx);
        group.channels.add(ty);
        group.channels.add(tz);

        group.channels.add(rx);
        group.channels.add(ry);
        group.channels.add(rz);

        /* Only emit scale channels when scale actually deviates from rest;
         * BBS defaults missing scale channels to 1, so this keeps clips lean. */
        if (scaleVaries)
        {
            group.channels.add(sx);
            group.channels.add(sy);
            group.channels.add(sz);
        }

        action.groups.put(nodeName, group);
    }

    /**
     * Finds i such that times[i] <= time < times[i+1], via binary search
     * (times is sorted ascending - Assimp stores keyframes in time order).
     * Caller must ensure times[0] < time < times[n-1]; the boundary cases
     * are handled by the early-returns in interpolateVector/interpolateQuat,
     * so i and i+1 are always valid indices here.
     */
    private static int findSegmentStart(double[] times, double time)
    {
        int result = Arrays.binarySearch(times, time);
        if (result >= 0)
        {
            return result;
        }

        int insertionPoint = -result - 1;
        return insertionPoint - 1;
    }

    private static Vector3f interpolateVector(double[] times, Vector3f[] values, double time)
    {
        int n = times.length;

        if (n == 0) return new Vector3f();
        if (time <= times[0]) return new Vector3f(values[0]);
        if (time >= times[n - 1]) return new Vector3f(values[n - 1]);

        int i = findSegmentStart(times, time);
        double span = times[i + 1] - times[i];
        float factor = span <= 0 ? 0f : (float) ((time - times[i]) / span);
        Vector3f result = new Vector3f(values[i]);
        result.lerp(values[i + 1], factor);
        return result;
    }

    private static Quaternionf interpolateQuat(double[] times, Quaternionf[] values, double time)
    {
        int n = times.length;

        if (n == 0) return new Quaternionf();
        if (time <= times[0]) return new Quaternionf(values[0]);
        if (time >= times[n - 1]) return new Quaternionf(values[n - 1]);

        int i = findSegmentStart(times, time);
        double span = times[i + 1] - times[i];
        float factor = span <= 0 ? 0f : (float) ((time - times[i]) / span);
        Quaternionf result = new Quaternionf(values[i]);
        result.slerp(values[i + 1], factor);
        return result.normalize();
    }

    /**
     * Converts a quaternion (x, y, z, w) to ZYX euler angles (radians),
     * matching the rotateZ -> rotateY -> rotateX application order used by
     * BOBJBone#applyTransformations. dest = (rotX, rotY, rotZ).
     */
    private static void quatToEulerZYX(Quaternionf q, Vector3f dest)
    {
        float x = q.x;
        float y = q.y;
        float z = q.z;
        float w = q.w;

        double sinrCosp = 2.0 * (w * x + y * z);
        double cosrCosp = 1.0 - 2.0 * (x * x + y * y);
        double roll = Math.atan2(sinrCosp, cosrCosp);

        double sinp = 2.0 * (w * y - z * x);
        double pitch;
        if (Math.abs(sinp) >= 1.0)
        {
            pitch = Math.copySign(Math.PI / 2.0, sinp);
        }
        else
        {
            pitch = Math.asin(sinp);
        }

        double sinyCosp = 2.0 * (w * z + x * y);
        double cosyCosp = 1.0 - 2.0 * (y * y + z * z);
        double yaw = Math.atan2(sinyCosp, cosyCosp);

        dest.set((float) roll, (float) pitch, (float) yaw);
    }
}