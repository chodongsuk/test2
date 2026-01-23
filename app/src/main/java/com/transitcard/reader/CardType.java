package com.transitcard.reader;

public enum CardType {
    TMONEY("티머니 (T-money)"),
    CASHBEE("캐시비 (Cashbee)"),
    HANPAY("한페이 (Hanpay)"),
    RAILPLUS("레일플러스 (Rail+)"),
    MPASS("엠패스 (M Pass)"),
    SEOUL_CITY_PASS("서울시티패스 (Seoul City Pass)"),
    KOREA_TOUR_CARD("코리아 투어 카드 (Korea Tour Card)"),
    EZL("이즐 (EZL)"),
    UNKNOWN("알 수 없는 카드");

    private final String displayName;

    CardType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
