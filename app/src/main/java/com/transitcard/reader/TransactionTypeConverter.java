package com.transitcard.reader;

import androidx.room.TypeConverter;

/**
 * TransactionType enum을 데이터베이스에 저장하기 위한 변환기
 *
 * Room은 기본적으로 enum을 저장할 수 없습니다.
 * 따라서 enum ↔ String 변환이 필요합니다.
 *
 * 예시:
 * - TransactionType.USE → "USE" (DB에 저장)
 * - "USE" → TransactionType.USE (DB에서 읽기)
 */
public class TransactionTypeConverter {

    /**
     * String을 TransactionType enum으로 변환
     * (데이터베이스 → 자바 객체)
     *
     * @param value DB에 저장된 문자열 (예: "USE", "CHARGE", "UNKNOWN")
     * @return TransactionType enum 값
     *
     * 동작:
     * 1. value가 null이면 → UNKNOWN 반환
     * 2. "USE" 문자열 → TransactionType.USE 반환
     * 3. "CHARGE" 문자열 → TransactionType.CHARGE 반환
     * 4. 잘못된 값이면 → UNKNOWN 반환
     */
    @TypeConverter  // ← Room에게 "이건 타입 변환 메서드야"라고 알려줌
    public static TransactionType toTransactionType(String value) {
        // null 체크
        if (value == null) {
            return TransactionType.UNKNOWN;
        }

        try {
            // 문자열을 enum으로 변환
            // 예: "USE" → TransactionType.USE
            return TransactionType.valueOf(value);

        } catch (IllegalArgumentException e) {
            // 잘못된 문자열이 들어오면 (예: "INVALID")
            // UNKNOWN 반환
            return TransactionType.UNKNOWN;
        }
    }

    /**
     * TransactionType enum을 String으로 변환
     * (자바 객체 → 데이터베이스)
     *
     * @param type TransactionType enum 값
     * @return DB에 저장할 문자열 (예: "USE", "CHARGE")
     *
     * 동작:
     * 1. type이 null이면 → "UNKNOWN" 반환
     * 2. TransactionType.USE → "USE" 반환
     * 3. TransactionType.CHARGE → "CHARGE" 반환
     */
    @TypeConverter  // ← Room에게 "이것도 타입 변환 메서드야"라고 알려줌
    public static String fromTransactionType(TransactionType type) {
        // null 체크
        if (type == null) {
            return TransactionType.UNKNOWN.name();  // "UNKNOWN" 문자열
        }

        // enum의 이름을 문자열로 변환
        // 예: TransactionType.USE → "USE"
        return type.name();
    }
}

/*
 * ===== 사용 예시 =====
 *
 * 1. 데이터 저장 시:
 *
 *    Transaction transaction = new Transaction();
 *    transaction.setTransactionType(TransactionType.USE);  // ← enum 값
 *
 *    cardDao.insert(transaction);
 *
 *    → Room이 자동으로 fromTransactionType() 호출
 *    → TransactionType.USE가 "USE" 문자열로 변환
 *    → DB에 "USE" 저장
 *
 *
 * 2. 데이터 읽기 시:
 *
 *    Transaction transaction = cardDao.getTransaction(1);
 *
 *    → Room이 DB에서 "USE" 문자열 읽음
 *    → 자동으로 toTransactionType() 호출
 *    → "USE"가 TransactionType.USE로 변환
 *
 *    TransactionType type = transaction.getTransactionType();
 *    // type = TransactionType.USE
 *
 *
 * ===== 데이터베이스에 저장되는 모습 =====
 *
 * transactions 테이블:
 *
 * | id | cardId | location | transactionType |
 * |----|--------|----------|-----------------|
 * | 1  | 1      | 강남역   | USE             |  ← 문자열로 저장
 * | 2  | 1      | 편의점   | CHARGE          |  ← 문자열로 저장
 * | 3  | 2      | 역삼역   | USE             |  ← 문자열로 저장
 *
 *
 * ===== 왜 필요한가? =====
 *
 * Room은 다음 타입들만 기본 지원:
 * - int, long, String, boolean 등
 *
 * enum은 기본 지원 안 함!
 *
 * TypeConverter 없이 enum 사용하면:
 * ❌ Error: Cannot figure out how to save this field into database
 *
 * TypeConverter 있으면:
 * ✅ OK: enum ↔ String 자동 변환!
 *
 *
 * ===== AppDatabase에 등록 필수! =====
 *
 * @Database(entities = {...}, version = 1)
 * @TypeConverters({TransactionTypeConverter.class})  ← 여기에 등록!
 * public abstract class AppDatabase extends RoomDatabase {
 *     ...
 * }
 */