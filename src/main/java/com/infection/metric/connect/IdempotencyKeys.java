package com.infection.metric.connect;

import com.infection.metric.common.Hashes;

/** 幂等键工具：发布用 sha256(indicator_code|definition_version)，合并用 sha256(package_version|target_definition_version)。 */
public final class IdempotencyKeys {
    private IdempotencyKeys() {}

    public static String publish(String indicatorCode, String definitionVersion) {
        return Hashes.sha256Of(indicatorCode, definitionVersion);
    }

    public static String merge(String packageVersion, String targetDefinitionVersion) {
        return Hashes.sha256Of(packageVersion, targetDefinitionVersion);
    }
}
