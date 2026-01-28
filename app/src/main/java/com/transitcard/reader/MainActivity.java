package com.transitcard.reader;

import android.app.PendingIntent;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import java.text.NumberFormat;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private NfcAdapter nfcAdapter;
    private NFCReader nfcReader;
    private PendingIntent pendingIntent;

    // UI Components
    private TextView statusTextView;
    private TextView scanInstructionTextView;
    private CardView cardInfoCard;
    private CardView transactionHistoryCard;
    private TextView cardTypeValue;
    private TextView cardNumberValue;
    private TextView balanceValue;
    private LinearLayout transactionHistoryContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate: Starting MainActivity");
        setContentView(R.layout.activity_main);

        // Initialize NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        nfcReader = new NFCReader();
        Log.d(TAG, "onCreate: NFC adapter initialized, isEnabled=" + (nfcAdapter != null ? nfcAdapter.isEnabled() : "null"));

        // Initialize UI components
        initializeViews();

        // Check NFC availability
        checkNfcAvailability();

        // Create pending intent for NFC
        Intent intent = new Intent(this, getClass());
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_MUTABLE
        );

        // Check if launched by NFC intent
        handleIntent(getIntent());
    }

    private void initializeViews() {
        statusTextView = findViewById(R.id.statusTextView);
        scanInstructionTextView = findViewById(R.id.scanInstructionTextView);
        cardInfoCard = findViewById(R.id.cardInfoCard);
        transactionHistoryCard = findViewById(R.id.transactionHistoryCard);
        cardTypeValue = findViewById(R.id.cardTypeValue);
        cardNumberValue = findViewById(R.id.cardNumberValue);
        balanceValue = findViewById(R.id.balanceValue);
        transactionHistoryContainer = findViewById(R.id.transactionHistoryContainer);
    }

    private void checkNfcAvailability() {
        if (nfcAdapter == null) {
            // NFC not supported
            showStatus(getString(R.string.nfc_not_supported));
            scanInstructionTextView.setVisibility(View.GONE);
        } else if (!nfcAdapter.isEnabled()) {
            // NFC is disabled
            showStatus(getString(R.string.nfc_disabled));
        } else {
            // NFC is enabled and ready
            hideStatus();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null && nfcAdapter.isEnabled()) {
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, null, null);
            hideStatus();
        } else {
            checkNfcAvailability();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(this);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "handleIntent: action=" + action);

        if (NfcAdapter.ACTION_TAG_DISCOVERED.equals(action) ||
                NfcAdapter.ACTION_TECH_DISCOVERED.equals(action) ||
                NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {

            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            Log.d(TAG, "handleIntent: Tag received, tag=" + (tag != null ? tag.toString() : "null"));
            if (tag != null) {
                String[] techList = tag.getTechList();
                Log.d(TAG, "handleIntent: Tag tech list:");
                for (String tech : techList) {
                    Log.d(TAG, "  - " + tech);
                }
                readCard(tag);
            }
        }
    }

    private void readCard(Tag tag) {
        Log.d(TAG, "readCard: Starting to read card");
        Log.d(TAG, "readCard: Tag ID = " + bytesToHex(tag.getId()));
        showStatus(getString(R.string.reading_card));

        // Read card in background
        new Thread(() -> {
            Log.d(TAG, "readCard: Background thread started");
            TransitCardData cardData = null;

            try {
                cardData = nfcReader.readCard(tag);
                Log.d(TAG, "readCard: Card data received, cardData=" + (cardData != null ? cardData.toString() : "null"));
            } catch (Exception e) {
                Log.e(TAG, "readCard: Exception during card read", e);
                e.printStackTrace();
            }

            // Update UI on main thread
            final TransitCardData finalCardData = cardData;
            runOnUiThread(() -> {
                hideStatus();
                if (finalCardData != null) {
                    Log.i(TAG, "readCard: Successfully read card - Type: " + finalCardData.getCardType() +
                            ", Number: " + finalCardData.getCardNumber() +
                            ", Balance: " + finalCardData.getBalance());
                    displayCardData(finalCardData);
                } else {
                    Log.w(TAG, "readCard: Failed to read card data - card data is null");
                    Toast.makeText(this, "카드를 읽을 수 없습니다. 티머니 또는 캐시비 카드인지 확인해주세요.", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void displayCardData(TransitCardData cardData) {
        Log.d(TAG, "displayCardData: Displaying card data");
        Log.d(TAG, "displayCardData: Card Type = " + cardData.getCardType().getDisplayName());
        Log.d(TAG, "displayCardData: Card Number = " + cardData.getCardNumber());
        Log.d(TAG, "displayCardData: Balance = " + cardData.getBalance());
        Log.d(TAG, "displayCardData: Transaction count = " +
                (cardData.getTransactionHistory() != null ? cardData.getTransactionHistory().size() : 0));

        // Show card detected message
        Toast.makeText(this, R.string.card_detected, Toast.LENGTH_SHORT).show();

        // Display card info
        cardTypeValue.setText(cardData.getCardType().getDisplayName());
        cardNumberValue.setText(formatCardNumber(cardData.getCardNumber()));

        // Display balance
        NumberFormat numberFormat = NumberFormat.getNumberInstance(Locale.KOREA);
        balanceValue.setText(numberFormat.format(cardData.getBalance()));

        // Show card info card
        cardInfoCard.setVisibility(View.VISIBLE);

        // Display transaction history if available
        if (cardData.getTransactionHistory() != null && !cardData.getTransactionHistory().isEmpty()) {
            displayTransactionHistory(cardData.getTransactionHistory());
            transactionHistoryCard.setVisibility(View.VISIBLE);
        } else {
            transactionHistoryCard.setVisibility(View.GONE);
        }

    }

    private void displayTransactionHistory(java.util.List<Transaction> transactions) {
        transactionHistoryContainer.removeAllViews();

        NumberFormat numberFormat = NumberFormat.getNumberInstance(Locale.KOREA);

        for (Transaction transaction : transactions) {
            View transactionView = getLayoutInflater().inflate(
                    R.layout.transaction_item,
                    transactionHistoryContainer,
                    false
            );

            TextView dateTextView = transactionView.findViewById(R.id.transactionDate);
            TextView locationTextView = transactionView.findViewById(R.id.transactionLocation);
            TextView amountTextView = transactionView.findViewById(R.id.transactionAmount);
            TextView balanceAfterTextView = transactionView.findViewById(R.id.transactionBalanceAfter);

            dateTextView.setText(transaction.getDate());
            locationTextView.setText(transaction.getLocation());
            amountTextView.setText(numberFormat.format(transaction.getAmount()) + " " + getString(R.string.won));
            balanceAfterTextView.setText(getString(R.string.balance_after) + ": " +
                    numberFormat.format(transaction.getBalanceAfter()) + " " + getString(R.string.won));

            transactionHistoryContainer.addView(transactionView);
        }
    }

    private String formatCardNumber(String cardNumber) {
        // Remove existing spaces first
        String digitsOnly = cardNumber.replace(" ", "");

        // Format with spaces: XXXX XXXX XXXX XXXX
        if (digitsOnly.length() >= 16) {
            return digitsOnly.substring(0, 4) + " " +
                    digitsOnly.substring(4, 8) + " " +
                    digitsOnly.substring(8, 12) + " " +
                    digitsOnly.substring(12, 16);
        } else if (digitsOnly.length() > 8) {
            // For shorter card numbers, format every 4 digits
            StringBuilder formatted = new StringBuilder();
            for (int i = 0; i < digitsOnly.length(); i++) {
                if (i > 0 && i % 4 == 0) {
                    formatted.append(" ");
                }
                formatted.append(digitsOnly.charAt(i));
            }
            return formatted.toString();
        }
        return cardNumber;
    }

    private void showStatus(String message) {
        statusTextView.setText(message);
        statusTextView.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        statusTextView.setVisibility(View.GONE);
    }

    private String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02X", b));
        }
        return result.toString();
    }
}