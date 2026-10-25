def discomfort_index(temperature: float | None, humidity: float | None) -> float | None:
    """不快指数 DI = 0.81T + 0.01H(0.99T - 14.3) + 46.3"""
    if temperature is None or humidity is None:
        return None
    t, h = temperature, humidity
    return round(0.81 * t + 0.01 * h * (0.99 * t - 14.3) + 46.3, 1)
