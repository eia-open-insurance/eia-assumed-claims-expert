package com.eia.assumedclaims.expert;

import java.util.UUID;

public record AcceptAdviceRequest(
        UUID acceptedBy,
        String note
) {}
