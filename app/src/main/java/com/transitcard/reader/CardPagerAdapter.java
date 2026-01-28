package com.transitcard.reader;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import com.transitcard.reader.R;
import com.transitcard.reader.Transaction;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CardPagerAdapter - ViewPager2용 어댑터
 *
 * 역할:
 * - ViewPager2에서 카드를 좌우로 스와이프하며 보여주는 어댑터
 * - 각 페이지에 카드 정보 + 거래내역 표시
 * - 카드 삭제 버튼 처리
 *
 * ViewPager2:
 * - 카드를 좌우로 넘기며 볼 수 있는 UI
 * - 책 넘기는 것처럼 페이지 전환
 *
 * RecyclerView.Adapter:
 * - ViewPager2는 내부적으로 RecyclerView 사용
 * - 따라서 RecyclerView.Adapter를 상속
 */
public class CardPagerAdapter extends RecyclerView.Adapter<CardPagerAdapter.CardViewHolder> {

    /**
     * 카드 데이터 리스트
     * CardWithTransactions = 카드 + 거래내역
     */
    private List<CardWithTransactions> cards = new ArrayList<>();

    /**
     * 카드 삭제 리스너
     * MainActivity에서 설정
     */
    private OnCardDeleteListener deleteListener;

    /**
     * 카드 삭제 리스너 인터페이스
     * MainActivity가 이 인터페이스를 구현
     */
    public interface OnCardDeleteListener {
        void onCardDelete(CardWithTransactions card);
    }

    /**
     * 삭제 리스너 설정
     * MainActivity에서 호출
     *
     * 예:
     * adapter.setOnCardDeleteListener(card -> {
     *     // 카드 삭제 로직
     * });
     */
    public void setOnCardDeleteListener(OnCardDeleteListener listener) {
        this.deleteListener = listener;
    }

    /**
     * 카드 데이터 설정
     * LiveData가 변경될 때마다 호출됨
     *
     * @param cards 새로운 카드 리스트
     *
     * notifyDataSetChanged():
     * - UI를 다시 그림
     * - ViewPager2가 자동으로 업데이트됨
     */
    public void setCards(List<CardWithTransactions> cards) {
        this.cards = cards;
        notifyDataSetChanged();  // UI 갱신
    }

    /**
     * ViewHolder 생성
     * ViewPager2가 페이지를 만들 때 호출
     *
     * @param parent ViewPager2
     * @param viewType 뷰 타입 (여기선 사용 안 함)
     * @return 생성된 ViewHolder
     */
    @NonNull
    @Override
    public CardViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // item_card_page.xml 레이아웃을 inflate
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_card_page, parent, false);
        return new CardViewHolder(view);
    }

    /**
     * 데이터를 ViewHolder에 바인딩
     * 각 페이지를 표시할 때 호출
     *
     * @param holder ViewHolder
     * @param position 위치 (0, 1, 2, ...)
     */
    @Override
    public void onBindViewHolder(@NonNull CardViewHolder holder, int position) {
        CardWithTransactions cardWithTrans = cards.get(position);
        holder.bind(cardWithTrans);  // 데이터 바인딩
    }

    /**
     * 전체 아이템 개수
     * @return 카드 개수
     */
    @Override
    public int getItemCount() {
        return cards.size();
    }

    /**
     * CardViewHolder - 각 카드 페이지를 관리하는 ViewHolder
     *
     * 역할:
     * - item_card_page.xml의 View들을 참조
     * - 카드 데이터를 View에 표시
     * - 거래내역 표시
     * - 삭제 버튼 처리
     */
    class CardViewHolder extends RecyclerView.ViewHolder {
        // UI 요소들
        private TextView cardTypeValue;              // 카드 종류 (티머니, 캐시비)
        private TextView cardNumberValue;            // 카드 번호
        private TextView balanceValue;               // 잔액
        private LinearLayout transactionHistoryContainer;  // 거래내역 컨테이너
        private CardView transactionHistoryCard;     // 거래내역 카드뷰
        private ImageButton deleteButton;            // 삭제 버튼

        /**
         * ViewHolder 생성자
         * findViewById로 View들 찾기
         */
        public CardViewHolder(@NonNull View itemView) {
            super(itemView);
            cardTypeValue = itemView.findViewById(R.id.cardTypeValue);
            cardNumberValue = itemView.findViewById(R.id.cardNumberValue);
            balanceValue = itemView.findViewById(R.id.balanceValue);
            transactionHistoryContainer = itemView.findViewById(R.id.transactionHistoryContainer);
            transactionHistoryCard = itemView.findViewById(R.id.transactionHistoryCard);
            deleteButton = itemView.findViewById(R.id.deleteButton);
        }

        /**
         * 카드 데이터를 UI에 바인딩
         *
         * @param cardWithTrans 카드 + 거래내역
         */
        public void bind(CardWithTransactions cardWithTrans) {
            // 숫자 포맷터 (한국 형식: 1,000,000)
            NumberFormat numberFormat = NumberFormat.getNumberInstance(Locale.KOREA);

            // 카드 정보 표시
            cardTypeValue.setText(cardWithTrans.card.getCardType());  // "티머니"
            cardNumberValue.setText(formatCardNumber(cardWithTrans.card.getCardNumber()));  // "1234 5678 9012 3456"
            balanceValue.setText(numberFormat.format(cardWithTrans.card.getBalance()));  // "50,000"

            // 삭제 버튼 클릭 리스너
            deleteButton.setOnClickListener(v -> {
                if (deleteListener != null) {
                    deleteListener.onCardDelete(cardWithTrans);  // MainActivity로 전달
                }
            });

            // 거래내역 표시
            if (cardWithTrans.transactions != null && !cardWithTrans.transactions.isEmpty()) {
                // 거래내역이 있으면 표시
                displayTransactionHistory(cardWithTrans.transactions);
                transactionHistoryCard.setVisibility(View.VISIBLE);
            } else {
                // 거래내역이 없으면 숨김
                transactionHistoryCard.setVisibility(View.GONE);
            }
        }

        /**
         * 거래내역 표시
         *
         * @param transactions 거래 리스트
         *
         * 동작:
         * 1. 기존 View들 제거
         * 2. 각 거래마다 transaction_item.xml inflate
         * 3. 거래 정보 표시
         * 4. 컨테이너에 추가
         */
        private void displayTransactionHistory(List<Transaction> transactions) {
            // 기존에 표시된 거래내역 제거
            transactionHistoryContainer.removeAllViews();

            // 숫자 포맷터
            NumberFormat numberFormat = NumberFormat.getNumberInstance(Locale.KOREA);

            // 각 거래 항목을 표시
            for (Transaction transaction : transactions) {
                // transaction_item.xml inflate
                View transactionView = LayoutInflater.from(itemView.getContext()).inflate(
                        R.layout.transaction_item,
                        transactionHistoryContainer,
                        false
                );

                // View 찾기
                TextView dateTextView = transactionView.findViewById(R.id.transactionDate);
                TextView locationTextView = transactionView.findViewById(R.id.transactionLocation);
                TextView amountTextView = transactionView.findViewById(R.id.transactionAmount);
                TextView balanceAfterTextView = transactionView.findViewById(R.id.transactionBalanceAfter);

                // 데이터 설정
                dateTextView.setText(transaction.getDate());                    // "2025-01-28"
                locationTextView.setText(transaction.getLocation());            // "강남역"
                amountTextView.setText(numberFormat.format(transaction.getAmount()) + " 원");  // "-1,400 원"
                balanceAfterTextView.setText("잔액: " +
                        numberFormat.format(transaction.getBalanceAfter()) + " 원");  // "잔액: 48,600 원"

                // 컨테이너에 추가
                transactionHistoryContainer.addView(transactionView);
            }
        }

        /**
         * 카드번호 포맷 (4자리씩 띄어쓰기)
         *
         * @param cardNumber 카드번호 (예: "1234567890123456")
         * @return 포맷된 카드번호 (예: "1234 5678 9012 3456")
         *
         * 동작:
         * - 16자리면: 4-4-4-4 형식
         * - 8자리 이상이면: 4자리씩 띄어쓰기
         * - 8자리 미만이면: 그대로
         */
        private String formatCardNumber(String cardNumber) {
            // 공백 제거
            String digitsOnly = cardNumber.replace(" ", "");

            if (digitsOnly.length() >= 16) {
                // 16자리: 4-4-4-4 형식
                return digitsOnly.substring(0, 4) + " " +
                        digitsOnly.substring(4, 8) + " " +
                        digitsOnly.substring(8, 12) + " " +
                        digitsOnly.substring(12, 16);
            } else if (digitsOnly.length() > 8) {
                // 8자리 이상: 4자리씩 띄어쓰기
                StringBuilder formatted = new StringBuilder();
                for (int i = 0; i < digitsOnly.length(); i++) {
                    if (i > 0 && i % 4 == 0) {
                        formatted.append(" ");
                    }
                    formatted.append(digitsOnly.charAt(i));
                }
                return formatted.toString();
            }
            // 8자리 미만: 그대로 반환
            return cardNumber;
        }
    }
}

/*
 * ===== 사용 예시 (MainActivity) =====
 *
 * // 1. Adapter 생성
 * CardPagerAdapter adapter = new CardPagerAdapter();
 *
 * // 2. ViewPager2에 설정
 * cardViewPager.setAdapter(adapter);
 *
 * // 3. 삭제 리스너 설정
 * adapter.setOnCardDeleteListener(card -> {
 *     showDeleteConfirmDialog(card);
 * });
 *
 * // 4. LiveData 관찰 (자동 업데이트)
 * cardDao.getAllCardsWithTransactions().observe(this, cards -> {
 *     adapter.setCards(cards);  // 데이터 변경 시 자동으로 UI 업데이트
 * });
 *
 *
 * ===== ViewPager2 동작 =====
 *
 * 카드 3개가 있을 때:
 *
 * [카드1] ← 현재 보이는 페이지
 *  카드2
 *  카드3
 *
 * 좌우 스와이프:
 *
 *  카드1
 * [카드2] ← 왼쪽으로 스와이프
 *  카드3
 *
 *  카드1
 *  카드2
 * [카드3] ← 왼쪽으로 스와이프
 *
 *
 * ===== 데이터 흐름 =====
 *
 * 1. Database에 카드 변경
 *    ↓
 * 2. LiveData가 변경 감지
 *    ↓
 * 3. MainActivity의 observe 콜백 호출
 *    ↓
 * 4. adapter.setCards(cards)
 *    ↓
 * 5. notifyDataSetChanged()
 *    ↓
 * 6. ViewPager2가 UI 업데이트
 *    ↓
 * 7. onBindViewHolder 호출
 *    ↓
 * 8. bind(cardWithTrans)
 *    ↓
 * 9. 화면에 카드 표시!
 */