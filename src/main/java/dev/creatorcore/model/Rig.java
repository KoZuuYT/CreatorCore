package dev.creatorcore.model;

import java.util.List;
import java.util.Map;
import org.joml.Matrix4d;

/**
 * Esqueleto de um modelo animado: ossos (pais antes dos filhos), o modelo de cada osso que tem cubos
 * e as animações. Posições e pivôs em unidades de modelo (16 = 1 bloco).
 *
 * @param boneModels  chave do osso -> JSON do modelo só com os cubos daquele osso
 * @param animations  nome -> animação
 */
public record Rig(List<Bone> bones, Map<String, String> boneModels, Map<String, Animation> animations) {

    /** @param parent chave do osso pai ou {@code null}; {@code bind} = rotação de repouso (graus) */
    public record Bone(String key, String parent, double[] pivot, double[] bind) {
    }

    /** Quadro-chave: instante em segundos e valor (x, y, z). */
    public record Keyframe(double time, double[] value) {
    }

    public record BoneAnim(List<Keyframe> rotation, List<Keyframe> position) {
    }

    public record Animation(String name, double length, boolean loop, Map<String, BoneAnim> bones) {
    }

    public boolean animated() {
        return !animations.isEmpty();
    }

    /** Id do arquivo/modelo de pack da parte do osso de índice {@code index}. */
    public static String partId(String modelId, int index) {
        return modelId + "__b" + index;
    }

    /**
     * Matrizes de cada osso (mesma ordem de {@link #bones()}) no instante {@code time} da animação
     * ({@code null} = pose de repouso). Cada matriz leva um ponto do modelo em repouso para a pose.
     */
    public Matrix4d[] pose(Animation animation, double time) {
        Matrix4d[] result = new Matrix4d[bones.size()];
        Map<String, Integer> indexOf = new java.util.HashMap<>();
        for (int i = 0; i < bones.size(); i++) {
            Bone bone = bones.get(i);
            indexOf.put(bone.key(), i);
            double[] rotation = bone.bind().clone();
            double[] position = new double[3];
            if (animation != null) {
                BoneAnim anim = animation.bones().get(bone.key());
                if (anim != null) {
                    double t = animation.loop() && animation.length() > 0
                            ? time % animation.length() : Math.min(time, animation.length());
                    double[] r = sample(anim.rotation(), t);
                    double[] p = sample(anim.position(), t);
                    for (int k = 0; k < 3; k++) {
                        rotation[k] += r[k];
                        position[k] += p[k];
                    }
                }
            }
            double[] pivot = bone.pivot();
            // posição · pivô · rotação(Z·Y·X) · -pivô
            Matrix4d local = new Matrix4d()
                    .translate(position[0], position[1], position[2])
                    .translate(pivot[0], pivot[1], pivot[2])
                    .rotateZ(Math.toRadians(rotation[2]))
                    .rotateY(Math.toRadians(rotation[1]))
                    .rotateX(Math.toRadians(rotation[0]))
                    .translate(-pivot[0], -pivot[1], -pivot[2]);
            Integer parent = bone.parent() == null ? null : indexOf.get(bone.parent());
            result[i] = parent == null ? local : new Matrix4d(result[parent]).mul(local);
        }
        return result;
    }

    private static double[] sample(List<Keyframe> frames, double t) {
        if (frames == null || frames.isEmpty()) {
            return new double[3];
        }
        if (frames.size() == 1 || t <= frames.get(0).time()) {
            return frames.get(0).value().clone();
        }
        for (int i = 1; i < frames.size(); i++) {
            Keyframe next = frames.get(i);
            if (t <= next.time()) {
                Keyframe prev = frames.get(i - 1);
                double span = next.time() - prev.time();
                double f = span <= 1e-9 ? 1 : (t - prev.time()) / span;
                double[] out = new double[3];
                for (int k = 0; k < 3; k++) {
                    out[k] = prev.value()[k] + (next.value()[k] - prev.value()[k]) * f;
                }
                return out;
            }
        }
        return frames.get(frames.size() - 1).value().clone();
    }
}
