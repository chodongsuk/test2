package com.transitcard.reader;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * CardEntity - 카드 정보 Entity (데이터베이스 테이블)
 *
 * 역할:
 * - 교통카드의 기본 정보를 저장하는 테이블 정의
 * - @Entity 어노테이션으로 "cards" 테이블 자동 생성
 *
 * 저장되는 정보:
 * - 카드번호, 카드종류, 잔액, 마지막 업데이트 시간
 *
 * 테이블 구조:
 * CREATE TABLE cards (
 *     id INTEGER PRIMARY KEY AUTOINCREMENT,
 *     cardNumber TEXT,
 *     cardType TEXT,
 *     balance INTEGER,
 *     lastUpdated INTEGER
 * )
 */
@Entity(tableName = "cards")  // "cards" 테이블로 생성
public class CardEntity {

    /**
     * 카드 고유 ID
     * @PrimaryKey: 기본키 (중복 불가, 각 카드를 구분하는 유일한 번호)
     * autoGenerate = true: 1, 2, 3... 자동 증가
     */
    @PrimaryKey(autoGenerate = true)
    private int id;

    /**
     * 카드번호
     * 예: "1234567890123456"
     * 중복 확인용으로 사용
     */
    private String cardNumber;

    /**
     * 카드 종류
     * 예: "티머니", "캐시비"
     */
    private String cardType;

    /**
     * 현재 잔액 (원 단위)
     * 예: 50000 (5만원)
     */
    private int balance;

    /**
     * 마지막 업데이트 시간
     * System.currentTimeMillis() 값 저장
     * 예: 1706432100000 (2025-01-28 12:00:00)
     */
    private long lastUpdated;

    /**
     * 빈 생성자 (Room 필수)
     * Room이 데이터베이스에서 읽을 때 사용
     */
    public CardEntity() {
    }

    /**
     * 일반 생성자 (카드 생성 시 사용)
     *
     * @param cardNumber 카드번호
     * @param cardType 카드종류
     * @param balance 잔액
     *
     * 사용 예:
     * CardEntity card = new CardEntity("1234567890", "티머니", 50000);
     */
    public CardEntity(String cardNumber, String cardType, int balance) {
        this.cardNumber = cardNumber;
        this.cardType = cardType;
        this.balance = balance;
        this.lastUpdated = System.currentTimeMillis();  // 현재 시간 자동 설정
    }

    // ==================== Getters and Setters ====================
    // Room이 데이터베이스와 객체 간 변환 시 사용

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getCardNumber() {
        return cardNumber;
    }

    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    public String getCardType() {
        return cardType;
    }

    public void setCardType(String cardType) {
        this.cardType = cardType;
    }

    public int getBalance() {
        return balance;
    }

    public void setBalance(int balance) {
        this.balance = balance;
    }

    public long getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }
}

/*
 * ===== 사용 예시 =====
 *
 * // 1. 새 카드 생성
 * CardEntity card = new CardEntity("1234567890", "티머니", 50000);
 *
 * // 2. 데이터베이스에 저장
 * cardDao.insertCard(card);
 * // id는 자동으로 1, 2, 3... 증가
 *
 * // 3. 카드 정보 수정
 * card.setBalance(30000);  // 잔액 변경
 * card.setLastUpdated(System.currentTimeMillis());
 * cardDao.updateCard(card);
 *
 * // 4. 카드 조회
 * CardEntity found = cardDao.getCardByNumber("1234567890");
 * int balance = found.getBalance();  // 30000
 *
 *
 * ===== 데이터베이스에 저장된 모습 =====
 *
 * cards 테이블:
 *
 * | id | cardNumber   | cardType | balance | lastUpdated     |
 * |----|--------------|----------|---------|-----------------|
 * | 1  | 1234567890   | 티머니   | 50000   | 1706432100000   |
 * | 2  | 9876543210   | 캐시비   | 30000   | 1706432200000   |
 * | 3  | 5555666677   | 티머니   | 25000   | 1706432300000   |
 *
 *
 * ===== 왜 Entity가 필요한가? =====
 *
 * Entity 없이 SQL:
 * String sql = "INSERT INTO cards (cardNumber, cardType, balance) VALUES (?, ?, ?)";
 * db.execSQL(sql, new Object[]{"1234567890", "티머니", 50000});
 * → 복잡하고 실수하기 쉬움
 *
 * Entity 사용:
 * CardEntity card = new CardEntity("1234567890", "티머니", 50000);
 * cardDao.insertCard(card);
 * → 간단하고 타입 안전
 */