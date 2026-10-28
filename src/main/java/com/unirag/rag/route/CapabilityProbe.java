package com.unirag.rag.route;

/** 一次候选范式能力探测结果。 */
public record CapabilityProbe(RagParadigm paradigm,
                              CapabilityAvailability state,
                              String reasonCode) {

    public CapabilityProbe {
        if (paradigm == null || state == null) {
            throw new IllegalArgumentException("capability probe paradigm/state must not be null");
        }
        if (state == CapabilityAvailability.READY) {
            if (reasonCode != null) {
                throw new IllegalArgumentException("READY probe must not carry reasonCode");
            }
        } else if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("non-READY probe must carry reasonCode");
        }
    }
}
