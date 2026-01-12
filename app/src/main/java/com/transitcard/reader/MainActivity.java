package com.transitcard.reader;

import android.app.PendingIntent;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import java.text.NumberFormat;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
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
        setContentView(R.layout.activity_main);

        // Initialize NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        nfcReader = new NFCReader();

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
        if (NfcAdapter.ACTION_TAG_DISCOVERED.equals(action) ||
                NfcAdapter.ACTION_TECH_DISCOVERED.equals(action) ||
                NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {

            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag != null) {
                readCard(tag);
            }
        }
    }

    private void readCard(Tag tag) {
        showStatus(getString(R.string.reading_card));

        // Read card in background
        new Thread(() -> {
            TransitCardData cardData = nfcReader.readCard(tag);

            // Update UI on main thread
            runOnUiThread(() -> {
                hideStatus();
                if (cardData != null) {
                    displayCardData(cardData);
                } else {
                    Toast.makeText(this, R.string.error_reading_card, Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private void displayCardData(TransitCardData cardData) {
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

        // Special handling for Hi-pass
        if (cardData.getCardType() == CardType.HIPASS) {
            Toast.makeText(this, R.string.hipass_not_supported, Toast.LENGTH_LONG).show();
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
        // Format card number with spaces for better readability
        if (cardNumber.length() > 8) {
            StringBuilder formatted = new StringBuilder();
            for (int i = 0; i < cardNumber.length(); i++) {
                if (i > 0 && i % 4 == 0) {
                    formatted.append(" ");
                }
                formatted.append(cardNumber.charAt(i));
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
}
