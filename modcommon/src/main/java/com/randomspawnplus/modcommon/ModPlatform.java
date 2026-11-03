package com.randomspawnplus.modcommon;

import java.nio.file.Path;

/**
 * Платформо-специфичные параметры, которые предоставляет конкретный загрузчик.
 */
public interface ModPlatform {

    Path configDir();
}