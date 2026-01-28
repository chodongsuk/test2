package com.transitcard.reader;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.transitcard.reader.CardEntity;
import com.transitcard.reader.CardWithTransactions;
import com.transitcard.reader.Transaction;

import java.util.List;

/**
 * CardDao - Data Access Object (데이터 접근 객체)
 *
 * 역할:
 * - 데이터베이스 작업을 정의하는 인터페이스
 * - "무엇을" 할지만 선언하면, Room이 "어떻게" 할지 자동 생성
 *
 * 특징:
 * - interface로 선언 (클래스 아님!)
 * - Room이 자동으로 구현체(CardDao_Impl) 생성
 * - SQL을 직접 작성할 필요 없음 (일부 제외)
 *
 * 비유:
 * - DAO = 은행 창구 (우리가 요청하는 곳)
 * - Room = 은행원 (실제로 일 처리)
 * - Database = 금고 (데이터 저장소)
 */
@Dao  // Room에게 "이건 DAO야"라고 알려주는 표시
public interface CardDao {

    // ==================== 카드 관련 ====================

    /**
     * 카드 삽입
     *
     * @param card 추가할 카드 객체
     * @return 생성된 카드의 id (1, 2, 3...)
     *
     * OnConflictStrategy.IGNORE:
     * - 같은 id가 있으면 무시하고 -1 반환
     * - 새로 추가되면 자동 생성된 id 반환
     *
     * Room이 자동 생성하는 SQL:
     * INSERT OR IGNORE INTO cards (cardNumber, cardType, balance, lastUpdated)
     * VALUES (?, ?, ?, ?)
     *
     * 사용 예:
     * CardEntity card = new CardEntity("1234567890", "티머니", 50000);
     * long id = cardDao.insertCard(card);  // id = 1 (새로 생성)
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertCard(CardEntity card);

    /**
     * 카드 업데이트
     *
     * @param card 수정할 카드 (id로 찾아서 업데이트)
     *
     * 동작:
     * - card.getId()로 기존 카드 찾기
     * - 모든 필드를 card의 값으로 변경
     *
     * Room이 자동 생성하는 SQL:
     * UPDATE cards
     * SET cardNumber=?, cardType=?, balance=?, lastUpdated=?
     * WHERE id=?
     *
     * 사용 예:
     * CardEntity card = cardDao.getCardByNumber("1234567890");
     * card.setBalance(30000);  // 잔액 변경
     * cardDao.updateCard(card);
     */
    @Update
    void updateCard(CardEntity card);

    /**
     * 카드 삭제
     *
     * @param card 삭제할 카드
     *
     * CASCADE 동작:
     * - Transaction 테이블에 CASCADE 설정되어 있음
     * - 카드 삭제 시 해당 카드의 거래내역도 자동 삭제
     *
     * Room이 자동 생성하는 SQL:
     * DELETE FROM cards WHERE id=?
     * (자동으로)
     * DELETE FROM transactions WHERE cardId=?
     *
     * 사용 예:
     * cardDao.deleteCard(card);
     * // 카드와 그 카드의 모든 거래내역이 삭제됨
     */
    @Delete
    void deleteCard(CardEntity card);

    /**
     * 카드번호로 카드 찾기
     *
     * @param cardNumber 찾을 카드번호 (예: "1234567890")
     * @return CardEntity 객체 (없으면 null)
     *
     * 용도:
     * - NFC 스캔 시 이미 등록된 카드인지 확인
     * - 중복 등록 방지
     *
     * LIMIT 1: 결과 1개만 가져옴 (성능 향상)
     *
     * 사용 예:
     * CardEntity existing = cardDao.getCardByNumber("1234567890");
     * if (existing != null) {
     *     // 이미 등록된 카드 → 업데이트
     * } else {
     *     // 새 카드 → 추가
     * }
     */
    @Query("SELECT * FROM cards WHERE cardNumber = :cardNumber LIMIT 1")
    CardEntity getCardByNumber(String cardNumber);

    /**
     * 모든 카드 조회 (LiveData) - 자동 업데이트
     *
     * @return LiveData로 감싼 카드 리스트
     *
     * LiveData의 마법:
     * - 데이터가 변경되면 자동으로 알려줌
     * - observe()로 변경 감지
     * - UI 자동 업데이트
     *
     * ORDER BY lastUpdated DESC:
     * - 최근 업데이트된 카드가 먼저
     *
     * 사용 예:
     * cardDao.getAllCardsLive().observe(this, cards -> {
     *     // 카드가 추가/삭제/수정될 때마다 자동으로 호출됨!
     *     adapter.setCards(cards);
     * });
     */
    @Query("SELECT * FROM cards ORDER BY lastUpdated DESC")
    LiveData<List<CardEntity>> getAllCardsLive();

    /**
     * 모든 카드 조회 (일반) - 한 번만 조회
     *
     * @return 카드 리스트
     *
     * LiveData와의 차이:
     * - LiveData: 자동 업데이트, 메인 스레드 OK
     * - 일반: 한 번만 조회, 백그라운드 스레드 필수
     *
     * 사용 예:
     * new Thread(() -> {
     *     List<CardEntity> cards = cardDao.getAllCards();
     *     for (CardEntity card : cards) {
     *         Log.d(TAG, card.getCardNumber());
     *     }
     * }).start();
     */
    @Query("SELECT * FROM cards ORDER BY lastUpdated DESC")
    List<CardEntity> getAllCards();

    /**
     * 카드와 거래내역 함께 조회 (LiveData)
     *
     * @return CardWithTransactions 리스트
     *
     * @androidx.room.Transaction:
     * - Room의 @Transaction 어노테이션
     * - 우리 Transaction 클래스와 이름이 같아서 풀 패키지명 사용
     * - 여러 쿼리를 하나의 트랜잭션으로 묶음
     *
     * Room이 자동으로:
     * 1. SELECT * FROM cards ...
     * 2. 각 카드마다 SELECT * FROM transactions WHERE cardId = ?
     * 3. CardWithTransactions 객체로 합침
     *
     * 사용 예:
     * cardDao.getAllCardsWithTransactions().observe(this, cards -> {
     *     for (CardWithTransactions c : cards) {
     *         Log.d(TAG, c.card.getCardNumber());
     *         for (Transaction t : c.transactions) {
     *             Log.d(TAG, "  " + t.getLocation());
     *         }
     *     }
     * });
     */
    @androidx.room.Transaction  // 풀 패키지명 (이름 충돌 방지)
    @Query("SELECT * FROM cards ORDER BY lastUpdated DESC")
    LiveData<List<CardWithTransactions>> getAllCardsWithTransactions();

    /**
     * 특정 카드와 거래내역 조회
     *
     * @param cardId 조회할 카드 ID
     * @return CardWithTransactions 객체
     *
     * 사용 예:
     * new Thread(() -> {
     *     CardWithTransactions card = cardDao.getCardWithTransactions(1);
     *     Log.d(TAG, "카드: " + card.card.getCardNumber());
     *     Log.d(TAG, "거래: " + card.transactions.size() + "개");
     * }).start();
     */
    @androidx.room.Transaction
    @Query("SELECT * FROM cards WHERE id = :cardId")
    CardWithTransactions getCardWithTransactions(int cardId);

    // ==================== 거래내역 관련 ====================

    /**
     * 거래내역 1개 삽입
     *
     * @param transaction 추가할 거래
     *
     * OnConflictStrategy.REPLACE:
     * - 같은 id가 있으면 덮어쓰기
     * - 보통은 새로 추가됨 (autoGenerate)
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertTransaction(Transaction transaction);

    /**
     * 거래내역 여러개 삽입
     *
     * @param transactions 추가할 거래 리스트
     *
     * 동작:
     * - List의 모든 거래를 하나의 트랜잭션으로 삽입
     * - 빠르고 효율적
     *
     * 주의:
     * - 각 Transaction에 cardId 설정 필수!
     *
     * 사용 예:
     * List<Transaction> transactions = cardData.getTransactionHistory();
     * for (Transaction t : transactions) {
     *     t.setCardId(cardId);  // cardId 설정!
     * }
     * cardDao.insertTransactions(transactions);
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertTransactions(List<Transaction> transactions);

    /**
     * 특정 카드의 거래내역 삭제
     *
     * @param cardId 삭제할 카드 ID
     *
     * 용도:
     * - 완전 교체 전략 (기존 삭제 → 새로 추가)
     *
     * 사용 예:
     * // 1. 기존 거래내역 전부 삭제
     * cardDao.deleteTransactionsByCardId(cardId);
     *
     * // 2. 새 거래내역 추가
     * cardDao.insertTransactions(newTransactions);
     *
     * → 항상 최신 상태 유지!
     */
    @Query("DELETE FROM transactions WHERE cardId = :cardId")
    void deleteTransactionsByCardId(int cardId);

    /**
     * 특정 카드의 거래내역 조회
     *
     * @param cardId 조회할 카드 ID
     * @return 거래 리스트
     *
     * ORDER BY timestamp DESC: 최신 거래가 먼저
     *
     * 사용 예:
     * new Thread(() -> {
     *     List<Transaction> transactions = cardDao.getTransactionsByCardId(1);
     *     for (Transaction t : transactions) {
     *         Log.d(TAG, t.getLocation() + ": " + t.getAmount() + "원");
     *     }
     * }).start();
     */
    @Query("SELECT * FROM transactions WHERE cardId = :cardId ORDER BY timestamp DESC")
    List<Transaction> getTransactionsByCardId(int cardId);
}

/*
 * ===== DAO 사용 흐름 =====
 *
 * 1. AppDatabase에서 DAO 가져오기
 *    AppDatabase db = AppDatabase.getInstance(context);
 *    CardDao cardDao = db.cardDao();
 *
 * 2. 백그라운드 스레드에서 작업
 *    new Thread(() -> {
 *        // INSERT
 *        CardEntity card = new CardEntity(...);
 *        long id = cardDao.insertCard(card);
 *
 *        // UPDATE
 *        card.setBalance(30000);
 *        cardDao.updateCard(card);
 *
 *        // SELECT
 *        CardEntity found = cardDao.getCardByNumber("1234567890");
 *
 *        // DELETE
 *        cardDao.deleteCard(found);
 *    }).start();
 *
 * 3. LiveData로 자동 업데이트 (메인 스레드 OK)
 *    cardDao.getAllCardsLive().observe(this, cards -> {
 *        // 자동으로 호출됨!
 *    });
 *
 *
 * ===== LiveData vs 일반 반환 =====
 *
 * LiveData<List<CardEntity>> getAllCardsLive();
 * - 메인 스레드에서 호출 OK
 * - observe()로 자동 업데이트
 * - 데이터 변경 시 자동으로 콜백 호출
 *
 * List<CardEntity> getAllCards();
 * - 백그라운드 스레드에서만 호출 가능
 * - 한 번만 조회
 * - 자동 업데이트 없음
 *
 *
 * ===== 메인 스레드 규칙 =====
 *
 * ❌ 메인 스레드에서 불가:
 * - insertCard()
 * - updateCard()
 * - deleteCard()
 * - getAllCards() (일반)
 * - getCardByNumber()
 *
 * ✅ 메인 스레드에서 가능:
 * - getAllCardsLive() (LiveData)
 * - getAllCardsWithTransactions() (LiveData)
 *
 *
 * ===== 완전 교체 전략 =====
 *
 * 카드 스캔 시 거래내역 갱신:
 *
 * private void updateTransactions(int cardId, List<Transaction> newTransactions) {
 *     // 1. 기존 거래 전부 삭제
 *     cardDao.deleteTransactionsByCardId(cardId);
 *
 *     // 2. cardId 설정
 *     for (Transaction t : newTransactions) {
 *         t.setCardId(cardId);
 *     }
 *
 *     // 3. 새 거래 추가
 *     cardDao.insertTransactions(newTransactions);
 * }
 *
 * 장점:
 * - 간단명료
 * - 중복 체크 불필요
 * - 항상 최신 상태
 */