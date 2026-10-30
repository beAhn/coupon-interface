package com.coupon.v7;

// v7-2 발급 결과 (boolean으로는 품절/중복/오류 구분 불가)
public enum IssueResult {
    SUCCESS,
    SOLD_OUT,
    DUPLICATE,
    ERROR;

    // ISSUE_SCRIPT 반환값 → 결과: 남은 재고(>= 0) 승인 / -1 품절 / -2 중복 / 그 외(null 포함) 오류
    public static IssueResult from(Long luaResult) {
        if (luaResult == null) {
            return ERROR;
        }
        if (luaResult >= 0) {
            return SUCCESS;
        }
        if (luaResult == -1) {
            return SOLD_OUT;
        }
        if (luaResult == -2) {
            return DUPLICATE;
        }
        return ERROR;
    }
}
