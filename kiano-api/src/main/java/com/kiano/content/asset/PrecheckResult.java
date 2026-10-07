package com.kiano.content.asset;

import java.util.List;
import java.util.Map;

/** Precheck outcome: flags plus metrics (ssim, occupancy, ocrWords, ocr). */
public record PrecheckResult(List<PrecheckFlag> flags, Map<String, Object> metrics) {
}
