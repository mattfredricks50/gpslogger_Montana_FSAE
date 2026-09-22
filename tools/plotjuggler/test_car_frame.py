"""Synthetic checks for car_frame.py: python tools/plotjuggler/test_car_frame.py"""
import numpy as np

import car_frame as cf


def rotation(axis, deg):
    axis = cf.unit(axis)
    a = np.radians(deg)
    k = np.array([[0, -axis[2], axis[1]], [axis[2], 0, -axis[0]], [-axis[1], axis[0], 0]])
    return np.eye(3) + np.sin(a) * k + (1 - np.cos(a)) * k @ k


def simulate(phone_from_car, seconds=120, rate=250, noise=0.05, seed=1):
    """Car frame: x forward, y left, z up. Drives accelerate/brake/corner; returns phone-frame logs."""
    rng = np.random.default_rng(seed)
    t = np.arange(0, seconds, 1 / rate)
    long_a = 4.0 * np.sin(2 * np.pi * t / 20)            # m/s^2, speeding up and braking
    lat_a = 6.0 * np.sin(2 * np.pi * t / 7 + 1.0)        # cornering both ways, + = left
    yaw = 0.5 * np.sin(2 * np.pi * t / 7 + 1.0)          # rad/s, + = left
    speed = 15 + np.cumsum(long_a) / rate
    # accelerometer reads acceleration + 1 g up
    car_acc = np.stack([long_a, lat_a, np.full_like(t, cf.G)], axis=1)
    car_gyr = np.stack([np.zeros_like(t), np.zeros_like(t), yaw], axis=1)
    acc = car_acc @ phone_from_car.T + rng.normal(0, noise, (len(t), 3))
    gyr = car_gyr @ phone_from_car.T
    gravity = phone_from_car @ np.array([0, 0, cf.G])
    gps_t = np.arange(0, seconds, 1.0)
    gps_speed = np.interp(gps_t, t, speed)
    return t, acc, gyr, gravity, gps_t, gps_speed, long_a, lat_a, yaw


def check(name, got, want, tol):
    err = np.max(np.abs(got - want))
    assert err < tol, f"{name}: max error {err:.3f} >= {tol}"
    print(f"  ok {name} (max error {err:.3f})")


def test_flat_nose_up_with_hint():
    # Phone flat, screen up, top toward the nose: phone +y = car forward, phone +x = car right
    # columns are car forward, left, up expressed in phone axes
    phone_from_car = np.array([[0, -1, 0], [1, 0, 0], [0, 0, 1]], dtype=float)
    t, acc, gyr, grav, *_ , long_a, lat_a, yaw = simulate(phone_from_car)
    cols, info = cf.car_frame(t, acc, grav, "+y", gyr_t=t, gyr_xyz=gyr, window_s=0.1)
    inner = slice(100, -100)
    check("long_g", cols["long_g"][inner], long_a[inner] / cf.G, 0.02)
    check("lat_g (+ right)", cols["lat_g"][inner], -lat_a[inner] / cf.G, 0.02)
    check("vert_g", cols["vert_g"][inner], 0, 0.02)
    check("yaw_rate_dps", cols["yaw_rate_dps"][inner], np.degrees(yaw[inner]), 0.5)


def test_tilted_mount_gps_fit():
    # Awkward mount: phone yawed 37 deg and tilted 25 deg in its holder; forward found from GPS
    phone_from_car = (rotation([1, 0.3, 0.2], 25) @ rotation([0, 0, 1], 37)).T
    t, acc, gyr, grav, gps_t, gps_speed, long_a, lat_a, yaw = simulate(phone_from_car)
    cols, info = cf.car_frame(t, acc, grav, "auto", gps_t=gps_t, gps_speed=gps_speed,
                              gyr_t=t, gyr_xyz=gyr, window_s=0.1)
    assert info["forward_source"] == "GPS fit", info
    print(f"  GPS fit: R^2 {info['gps_fit']['r2']:.3f}, gain {info['gps_fit']['gain']:.3f}")
    inner = slice(100, -100)
    check("long_g", cols["long_g"][inner], long_a[inner] / cf.G, 0.05)
    check("lat_g (+ right)", cols["lat_g"][inner], -lat_a[inner] / cf.G, 0.05)
    check("yaw_rate_dps", cols["yaw_rate_dps"][inner], np.degrees(yaw[inner]), 1.0)


def test_auto_without_gps_falls_back():
    phone_from_car = np.eye(3)
    t, acc, gyr, grav, *_ = simulate(phone_from_car, seconds=10)
    cols, info = cf.car_frame(t, acc, grav, "auto")
    assert info["forward_source"].startswith("assumed +y"), info
    print("  ok auto without GPS falls back to +y")


def test_vertical_hint_rejected():
    try:
        cf.forward_from_hint(np.array([0, 0, 1.0]), "+z")
    except ValueError:
        print("  ok vertical forward axis rejected")
        return
    raise AssertionError("expected ValueError")


if __name__ == "__main__":
    for fn in (test_flat_nose_up_with_hint, test_tilted_mount_gps_fit,
               test_auto_without_gps_falls_back, test_vertical_hint_rejected):
        print(fn.__name__)
        fn()
    print("all passed")
