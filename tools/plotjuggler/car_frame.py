"""Phone-frame IMU -> car-frame accelerations for G-G plots.

The phone logs raw accelerometer (m/s^2, gravity included) and gyroscope (rad/s) in its own axes,
mounted however it happens to sit in the car. This works out the car axes:

  * up:      from the stationary calibration in .meta (mean accelerometer reading = gravity)
  * forward: either a phone axis you name (projected level), or fitted from GPS: while the car
             speeds up or slows down, horizontal acceleration points along forward, so the level
             direction whose acceleration best matches d(speed)/dt is forward

Output columns (car frame, gravity removed, smoothed):
  long_g        + accelerating, - braking
  lat_g         + accelerating toward the car's right, i.e. turning right
  vert_g        + up, 0 at rest
  yaw_rate_dps  + turning left (counter-clockwise seen from above)

Assumes the phone doesn't move in its mount after the calibration at Start, and that the car
was roughly level then.
"""

import numpy as np

G = 9.80665

AXES = {
    "+x": (1, 0, 0), "-x": (-1, 0, 0),
    "+y": (0, 1, 0), "-y": (0, -1, 0),
    "+z": (0, 0, 1), "-z": (0, 0, -1),
}


def unit(v):
    v = np.asarray(v, dtype=float)
    n = np.linalg.norm(v)
    if n == 0:
        raise ValueError("zero-length vector")
    return v / n


def level_basis(up):
    """Two orthonormal vectors perpendicular to up (any rotation about up)."""
    helper = np.array([1.0, 0, 0]) if abs(up[0]) < 0.9 else np.array([0, 1.0, 0])
    e1 = unit(helper - np.dot(helper, up) * up)
    e2 = np.cross(up, e1)
    return e1, e2


def forward_from_hint(up, axis):
    """Level forward direction from a phone axis like '+y' (the axis pointing at the car's nose)."""
    f = np.array(AXES[axis], dtype=float)
    f_level = f - np.dot(f, up) * up
    if np.linalg.norm(f_level) < 0.3:
        raise ValueError(f"{axis} is nearly vertical in this mount, so it can't be the forward axis")
    return unit(f_level)


def forward_from_gps(up, acc_t, acc_xyz, gps_t, gps_speed, min_dv_std=0.3, min_r2=0.5):
    """Fits the level direction whose acceleration best explains GPS speed changes.

    Returns (forward, info) or (None, info) when there isn't enough speed variation to trust.
    """
    info = {}
    ok = np.isfinite(gps_speed)
    gps_t, gps_speed = gps_t[ok], gps_speed[ok]
    if len(gps_t) < 10:
        info["reason"] = f"only {len(gps_t)} GPS fixes with speed"
        return None, info

    e1, e2 = level_basis(up)
    h1, h2 = acc_xyz @ e1, acc_xyz @ e2
    rows, target = [], []
    for i in range(len(gps_t) - 1):
        t0, t1 = gps_t[i], gps_t[i + 1]
        dt = t1 - t0
        if dt <= 0 or dt > 2.5:
            continue
        lo, hi = np.searchsorted(acc_t, [t0, t1])
        if hi - lo < 5:
            continue
        rows.append((h1[lo:hi].mean(), h2[lo:hi].mean()))
        target.append((gps_speed[i + 1] - gps_speed[i]) / dt)
    if len(rows) < 10:
        info["reason"] = f"only {len(rows)} usable GPS intervals"
        return None, info

    a = np.array(rows)
    y = np.array(target)
    info["dv_std_mps2"] = float(y.std())
    if y.std() < min_dv_std:
        info["reason"] = "not enough speeding up / slowing down in the GPS data"
        return None, info

    (c, s), *_ = np.linalg.lstsq(a, y, rcond=None)
    pred = a @ np.array([c, s])
    r2 = 1 - np.sum((y - pred) ** 2) / np.sum((y - y.mean()) ** 2)
    info.update(r2=float(r2), gain=float(np.hypot(c, s)), intervals=len(rows))
    if r2 < min_r2:
        info["reason"] = f"poor fit (R^2 {r2:.2f})"
        return None, info
    return unit(c * e1 + s * e2), info


def smooth(x, t, window_s):
    """Centered moving average over about window_s seconds; edges use the shorter window."""
    if window_s <= 0 or len(x) < 3:
        return x
    dt = np.median(np.diff(t))
    n = max(1, int(round(window_s / dt)))
    if n <= 1:
        return x
    kernel = np.ones(n)
    num = np.convolve(x, kernel, mode="same")
    den = np.convolve(np.ones_like(x), kernel, mode="same")
    return num / den


def car_frame(acc_t, acc_xyz, gravity, forward_axis="auto", gps_t=None, gps_speed=None,
              gyr_t=None, gyr_xyz=None, window_s=0.1):
    """Returns (columns dict, info dict). columns has time_s, long_g, lat_g, vert_g[, yaw_rate_dps]."""
    up = unit(gravity)
    info = {"gravity_mps2": float(np.linalg.norm(gravity))}

    forward = None
    if forward_axis == "auto":
        if gps_t is not None and len(gps_t):
            forward, fit = forward_from_gps(up, acc_t, acc_xyz, gps_t, gps_speed)
            info["gps_fit"] = fit
        if forward is None:
            info["forward_source"] = "assumed +y (top of phone toward the nose); no usable GPS fit"
            forward = forward_from_hint(up, "+y")
        else:
            info["forward_source"] = "GPS fit"
    else:
        forward = forward_from_hint(up, forward_axis)
        info["forward_source"] = f"phone {forward_axis} axis"

    right = np.cross(forward, up)
    info["forward_in_phone_axes"] = [round(float(v), 3) for v in forward]

    long_g = smooth(acc_xyz @ forward, acc_t, window_s) / G
    lat_g = smooth(acc_xyz @ right, acc_t, window_s) / G
    vert_g = (smooth(acc_xyz @ up, acc_t, window_s) - np.linalg.norm(gravity)) / G
    cols = {"time_s": acc_t, "long_g": long_g, "lat_g": lat_g, "vert_g": vert_g}

    if gyr_t is not None and len(gyr_t) > 1:
        yaw = np.degrees(smooth(gyr_xyz @ up, gyr_t, window_s))
        cols["yaw_rate_dps"] = np.interp(acc_t, gyr_t, yaw)
    return cols, info


def to_csv(cols):
    names = list(cols)
    decimals = {"time_s": 6, "yaw_rate_dps": 3}
    out = [",".join(names)]
    data = [cols[n] for n in names]
    for i in range(len(data[0])):
        out.append(",".join(f"{data[j][i]:.{decimals.get(n, 4)}f}" for j, n in enumerate(names)))
    return "\n".join(out) + "\n"


def read_csv_columns(text, names):
    """Parses a stream CSV into float arrays for the named columns (missing cells become NaN)."""
    lines = text.splitlines()
    header = lines[0].split(",")
    idx = [header.index(n) for n in names]
    cols = [[] for _ in names]
    for line in lines[1:]:
        if not line:
            continue
        cells = line.split(",")
        for k, i in enumerate(idx):
            c = cells[i] if i < len(cells) else ""
            cols[k].append(float(c) if c else np.nan)
    return [np.array(c) for c in cols]
