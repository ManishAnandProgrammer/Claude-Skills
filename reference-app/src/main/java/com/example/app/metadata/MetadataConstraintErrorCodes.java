package com.example.app.metadata;

import com.example.app.common.exception.ConstraintErrorCodes;
import com.example.app.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
class MetadataConstraintErrorCodes implements ConstraintErrorCodes {

    @Override
    public Map<String, ErrorCode> byConstraintName() {
        return Map.of("uq_configuration_metadata_key", ErrorCode.METADATA_KEY_TAKEN);
    }
}
