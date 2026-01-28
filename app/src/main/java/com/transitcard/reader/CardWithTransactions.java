package com.transitcard.reader;

import androidx.room.Embedded;
import androidx.room.Relation;

import com.transitcard.reader.Transaction;

import java.util.List;

/**
 * CardWithTransactions - 카드 + 거래내역 조합 클래스
 *
 * 역할:
 * - 카드 1개와 그 카드의 모든 거래내역을 함께 담는 컨테이너
 * - Room의 @Relation을 사용해서 자동으로 데이터 결합
 *
 * 왜 필요한가?
 * - 카드 정보만: CardEntity
 * - 거래내역만: List<Transaction>
 * - 둘을 따로 조회하면 번거로움
 * - CardWithTransactions로 한 번에 가져오기!
 *
 * 비유:
 * - CardEntity = 통장
 * - Transaction = 거래 내역서
 * - CardWithTransactions = 통장 + 거래내역서를 함께 든 봉투
 */
public class CardWithTransactions {

    /**
     * 카드 정보
     * @Embedded: CardEntity의 모든 필드를 포함
     *
     * 예:
     * card.getCardNumber() → "1234567890"
     * card.getBalance() → 50000
     */
    @Embedded
    public CardEntity card;

    /**
     * 거래내역 리스트
     * @Relation: cards와 transactions 테이블을 자동으로 연결
     *
     * parentColumn = "id": CardEntity의 id 컬럼
     * entityColumn = "cardId": Transaction의 cardId 컬럼
     *
     * Room이 자동으로:
     * 1. SELECT * FROM cards WHERE id = ?
     * 2. SELECT * FROM transactions WHERE cardId = ?
     * 3. 자동으로 합쳐서 반환
     *
     * 예:
     * transactions.size() → 5 (거래 5개)
     * transactions.get(0).getLocation() → "강남역"
     */
    @Relation(
            parentColumn = "id",      // 부모(CardEntity)의 컬럼
            entityColumn = "cardId"   // 자식(Transaction)의 컬럼
    )
    public List<Transaction> transactions;
}

/*
 * ===== 사용 예시 =====
 *
 * // 1. 카드와 거래내역 함께 조회
 * CardWithTransactions result = cardDao.getCardWithTransactions(1);
 *
 * // 2. 카드 정보 접근
 * String cardNumber = result.card.getCardNumber();  // "1234567890"
 * int balance = result.card.getBalance();           // 50000
 *
 * // 3. 거래내역 접근
 * List<Transaction> transactions = result.transactions;
 * for (Transaction t : transactions) {
 *     Log.d(TAG, t.getLocation() + ": " + t.getAmount());
 * }
 * // 출력:
 * // 강남역: -1400
 * // 역삼역: -1400
 * // 선릉역: -1400
 *
 *
 * ===== ViewPager에서 사용 =====
 *
 * // 모든 카드와 거래내역 조회
 * cardDao.getAllCardsWithTransactions().observe(this, cards -> {
 *     // cards는 List<CardWithTransactions>
 *     adapter.setCards(cards);
 * });
 *
 * // Adapter에서
 * public void bind(CardWithTransactions cardWithTrans) {
 *     // 카드 정보 표시
 *     cardTypeTextView.setText(cardWithTrans.card.getCardType());
 *     balanceTextView.setText(cardWithTrans.card.getBalance());
 *
 *     // 거래내역 표시
 *     displayTransactions(cardWithTrans.transactions);
 * }
 *
 *
 * ===== Room이 자동으로 하는 일 =====
 *
 * 우리가 호출:
 * CardWithTransactions result = cardDao.getCardWithTransactions(1);
 *
 * Room이 내부에서:
 *
 * // 1단계: 카드 조회
 * SELECT * FROM cards WHERE id = 1
 * → CardEntity card = ...
 *
 * // 2단계: 그 카드의 거래내역 조회
 * SELECT * FROM transactions WHERE cardId = 1
 * → List<Transaction> transactions = ...
 *
 * // 3단계: 자동으로 합치기
 * CardWithTransactions result = new CardWithTransactions();
 * result.card = card;
 * result.transactions = transactions;
 * return result;
 *
 * 우리는 이 모든 과정을 몰라도 됨!
 *
 *
 * ===== 데이터 구조 =====
 *
 * CardWithTransactions {
 *     card: {
 *         id: 1
 *         cardNumber: "1234567890"
 *         cardType: "티머니"
 *         balance: 50000
 *         lastUpdated: 1706432100000
 *     }
 *     transactions: [
 *         {
 *             id: 1
 *             cardId: 1
 *             date: "2025-01-28"
 *             location: "강남역"
 *             amount: -1400
 *             balanceAfter: 48600
 *         },
 *         {
 *             id: 2
 *             cardId: 1
 *             date: "2025-01-28"
 *             location: "역삼역"
 *             amount: -1400
 *             balanceAfter: 47200
 *         }
 *     ]
 * }
 *
 *
 * ===== @Embedded vs @Relation =====
 *
 * @Embedded (포함):
 * - CardEntity의 필드들을 직접 포함
 * - card.id, card.cardNumber 등 직접 접근
 *
 * @Relation (연결):
 * - 별도 테이블의 데이터를 자동으로 가져옴
 * - 1:N 관계 (카드 1개 : 거래 여러 개)
 *
 *
 * ===== 비교 =====
 *
 * CardWithTransactions 없이:
 * CardEntity card = cardDao.getCard(1);
 * List<Transaction> transactions = cardDao.getTransactionsByCardId(1);
 * // 2번 조회 필요
 *
 * CardWithTransactions 사용:
 * CardWithTransactions result = cardDao.getCardWithTransactions(1);
 * // 1번 호출로 모든 데이터!
 */