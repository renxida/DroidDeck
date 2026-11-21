package claude.phone;

/** A critically damped spring. Retargeting mid-flight keeps velocity, so state changes never pop. */
final class Spring {
  float value, velocity, target;
  float stiffness;

  Spring(float v, float stiffness) { value = target = v; this.stiffness = stiffness; }

  /** Advances by dt seconds. Returns true while still moving. */
  boolean step(float dt) {
    if (dt > 0.05f) dt = 0.05f;
    float damping = 2f * (float) Math.sqrt(stiffness);
    int n = 2; float h = dt / n;
    for (int i = 0; i < n; i++) {
      float a = stiffness * (target - value) - damping * velocity;
      velocity += a * h;
      value += velocity * h;
    }
    if (Math.abs(target - value) < 1e-4f && Math.abs(velocity) < 1e-3f) { value = target; velocity = 0; return false; }
    return true;
  }
}
