package com.transitcard.reader;

import android.nfc.tech.IsoDep;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class TMoneyParser implements CardParser {
    private static final String TAG = "TMoneyParser";

    @Override
    public TransitCardData parse(IsoDep isoDep, byte[] cardId) {
        try {
            // Read balance
            int balance = readBalance(isoDep);

            // Read transaction history
            List<Transaction> transactions = readTransactionHistory(isoDep);

            return new TransitCardData(
                    CardType.TMONEY,
                    bytesToHex(cardId),
                    balance,
                    transactions
            );
        } catch (Exception e) {
            Log.e(TAG, "Error parsing T-money card", e);
            // Return basic info even if detailed parsing fails
            return new TransitCardData(
                    CardType.TMONEY,
                    bytesToHex(cardId),
                    0,
                    new ArrayList<>()
            );
        }
    }

    private int readBalance(IsoDep isoDep) {
        try {
            Log.d(TAG, "Starting balance read (AID already selected by NFCReader)...");

            // AID is already selected by NFCReader, directly read balance
            byte[] balanceCommand = new byte[]{
                    (byte) 0x90, (byte) 0x4C, (byte) 0x00, (byte) 0x00,
                    (byte) 0x04
            };
            Log.d(TAG, "Sending balance command: " + bytesToHex(balanceCommand));
            byte[] response = isoDep.transceive(balanceCommand);
            Log.d(TAG, "Balance response (" + response.length + " bytes): " + bytesToHex(response));

            if (response.length >= 6) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;
                Log.d(TAG, "Balance SW: " + Integer.toHexString(sw1) + " " + Integer.toHexString(sw2));

                if (sw1 == 0x90 && sw2 == 0x00) {
                    // Balance is stored in first 4 bytes (big-endian)
                    int balance = ((response[0] & 0xFF) << 24) |
                                 ((response[1] & 0xFF) << 16) |
                                 ((response[2] & 0xFF) << 8) |
                                 (response[3] & 0xFF);
                    Log.d(TAG, "Parsed balance: " + balance + " won");
                    return balance;
                } else {
                    Log.e(TAG, "Balance command failed with SW: " + Integer.toHexString(sw1) + Integer.toHexString(sw2));
                }
            } else {
                Log.e(TAG, "Balance response too short: " + response.length);
            }
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "Error reading balance", e);
            return 0;
        }
    }

    private List<Transaction> readTransactionHistory(IsoDep isoDep) {
        List<Transaction> transactions = new ArrayList<>();

        try {
            // Read transaction records (up to 20 records)
            for (int recordNum = 0; recordNum < 20; recordNum++) {
                byte[] command = new byte[]{
                        (byte) 0x90, (byte) 0xB2, // CLA, INS (Read Record)
                        (byte) recordNum, (byte) 0x00, // P1 (record number), P2
                        (byte) 0x10                  // Le (16 bytes)
                };

                byte[] response = isoDep.transceive(command);
                if (response.length >= 18) { // 16 bytes data + 2 bytes SW
                    int sw1 = response[response.length - 2] & 0xFF;
                    int sw2 = response[response.length - 1] & 0xFF;

                    if (sw1 == 0x90 && sw2 == 0x00) {
                        Transaction transaction = parseTransactionRecord(response);
                        if (transaction != null) {
                            transactions.add(transaction);
                        }
                    } else {
                        break; // No more records
                    }
                } else {
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading transaction history", e);
        }

        // Return last 10 transactions
        return transactions.size() > 10 ? transactions.subList(0, 10) : transactions;
    }

    private Transaction parseTransactionRecord(byte[] data) {
        try {
            // Parse transaction data (this is simplified)
            // Real format would need proper decoding based on T-money specifications
            int amount = ByteBuffer.wrap(data, 4, 4).getInt();
            int balanceAfter = ByteBuffer.wrap(data, 8, 4).getInt();

            return new Transaction(
                    "2024-01-01", // Would parse from actual data
                    "서울역",       // Would parse from actual data
                    amount,
                    balanceAfter,
                    amount < 0 ? TransactionType.USE : TransactionType.CHARGE
            );
        } catch (Exception e) {
            Log.e(TAG, "Error parsing transaction record", e);
            return null;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02X", b));
        }
        return result.toString();
    }
}
