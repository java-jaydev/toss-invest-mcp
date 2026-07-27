package dev.jaydev.tossmcp.trading;

import java.util.List;

/**
 * 안전게이트 판정 결과.
 * checks 는 통과한 검사를 사람이 읽을 수 있게 남긴 것이라 응답에 그대로 실린다.
 */
public record GuardDecision(Outcome outcome, String reason, List<String> checks) {
    public enum Outcome { ALLOW, DRY_RUN, REJECT }
}
