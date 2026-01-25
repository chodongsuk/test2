package com.transitcard.reader;

import android.nfc.tech.IsoDep;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class EZLParser implements CardParser {
    private static final String TAG = "EZLParser";

    @Override
    public TransitCardData parse(IsoDep isoDep, byte[] cardId) {
        Log.d(TAG, "=== EZLParser.parse: Starting EZL card parsing ===");
        Log.d(TAG, "parse: Card ID = " + bytesToHex(cardId));
        try {
            // Read balance
            Log.d(TAG, "parse: Reading balance...");
            int balance = readBalance(isoDep);
            Log.d(TAG, "parse: Balance read = " + balance);

            // Read transaction history
            Log.d(TAG, "parse: Reading transaction history...");
            List<Transaction> transactions = readTransactionHistory(isoDep);
            Log.d(TAG, "parse: Transactions read = " + transactions.size());

            Log.i(TAG, "parse: SUCCESS - EZL card parsed, balance=" + balance);
            return new TransitCardData(
                    CardType.EZL,
                    bytesToHex(cardId),
                    balance,
                    transactions
            );
        } catch (Exception e) {
            Log.e(TAG, "parse: Error parsing EZL card", e);
            // Return basic info even if detailed parsing fails
            return new TransitCardData(
                    CardType.EZL,
                    bytesToHex(cardId),
                    0,
                    new ArrayList<>()
            );
        }
    }

    private int readBalance(IsoDep isoDep) {
        try {
            // Read balance from file
            // Command: Read Record (File 04, Record 00)
            byte[] command = new byte[]{
                    (byte) 0x90, (byte) 0xB0, // CLA, INS
                    (byte) 0x00, (byte) 0x00, // P1, P2
                    (byte) 0x04                  // Le
            };

            Log.d(TAG, "readBalance: Sending command = " + bytesToHex(command));
            byte[] response = isoDep.transceive(command);
            Log.d(TAG, "readBalance: Response = " + bytesToHex(response));
            Log.d(TAG, "readBalance: Response length = " + response.length);

            if (response.length >= 6) {
                // Balance is typically stored in bytes 0-3 (big-endian)
                int balance = ByteBuffer.wrap(response, 0, 4).getInt();
                Log.d(TAG, "readBalance: Parsed balance = " + balance);
                return balance;
            } else {
                Log.w(TAG, "readBalance: Response too short, returning 0");
                return 0;
            }
        } catch (Exception e) {
            Log.e(TAG, "readBalance: Error reading balance", e);
            return 0;
        }
    }

    private List<Transaction> readTransactionHistory(IsoDep isoDep) {
        Log.d(TAG, "readTransactionHistory: Starting to read transactions");
        List<Transaction> transactions = new ArrayList<>();

        try {
            // Read transaction records (up to 20 records)
            for (int recordNum = 0; recordNum < 20; recordNum++) {
                byte[] command = new byte[]{
                        (byte) 0x90, (byte) 0xB2, // CLA, INS (Read Record)
                        (byte) recordNum, (byte) 0x00, // P1 (record number), P2
                        (byte) 0x10                  // Le (16 bytes)
                };

                Log.d(TAG, "readTransactionHistory: Record " + recordNum + " command = " + bytesToHex(command));
                byte[] response = isoDep.transceive(command);
                Log.d(TAG, "readTransactionHistory: Record " + recordNum + " response = " + bytesToHex(response) + " (length=" + response.length + ")");

                if (response.length >= 18) { // 16 bytes data + 2 bytes SW
                    int sw1 = response[response.length - 2] & 0xFF;
                    int sw2 = response[response.length - 1] & 0xFF;
                    Log.d(TAG, "readTransactionHistory: Record " + recordNum + " SW = " + String.format("%02X%02X", sw1, sw2));

                    if (sw1 == 0x90 && sw2 == 0x00) {
                        Transaction transaction = parseTransactionRecord(response);
                        if (transaction != null) {
                            transactions.add(transaction);
                            Log.d(TAG, "readTransactionHistory: Record " + recordNum + " parsed successfully");
                        }
                    } else {
                        Log.d(TAG, "readTransactionHistory: No more records (SW != 9000)");
                        break; // No more records
                    }
                } else {
                    Log.d(TAG, "readTransactionHistory: Response too short, stopping");
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "readTransactionHistory: Error reading transaction history", e);
        }

        Log.d(TAG, "readTransactionHistory: Total transactions found = " + transactions.size());
        // Return last 10 transactions
        return transactions.size() > 10 ? transactions.subList(0, 10) : transactions;
    }

    private Transaction parseTransactionRecord(byte[] data) {
        try {
            // Parse transaction data (this is simplified)
            // Real format would need proper decoding based on EZL specifications
            int amount = ByteBuffer.wrap(data, 4, 4).getInt();
            int balanceAfter = ByteBuffer.wrap(data, 8, 4).getInt();

            return new Transaction(
                    "2024-01-01", // Would parse from actual data
                    "이즐 사용처",     // Would parse from actual data
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
