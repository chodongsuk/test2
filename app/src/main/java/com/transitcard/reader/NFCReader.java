package com.transitcard.reader;

import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.tech.NfcA;
import android.util.Log;

public class NFCReader {
    private static final String TAG = "NFCReader";

    public TransitCardData readCard(Tag tag) {
        Log.d(TAG, "=== readCard: Starting card read ===");
        try {
            byte[] id = tag.getId();
            Log.d(TAG, "readCard: Card ID = " + bytesToHex(id));
            Log.d(TAG, "readCard: Card ID length = " + id.length + " bytes");

            // Try IsoDep first (most common for Korean transit cards)
            IsoDep isoDep = IsoDep.get(tag);
            Log.d(TAG, "readCard: IsoDep available = " + (isoDep != null));
            if (isoDep != null) {
                return readIsoDepCard(isoDep, id);
            }

            // Try NfcA
            NfcA nfcA = NfcA.get(tag);
            Log.d(TAG, "readCard: NfcA available = " + (nfcA != null));
            if (nfcA != null) {
                return readNfcACard(nfcA, id);
            }

            Log.w(TAG, "readCard: No supported NFC technology found");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "readCard: Error reading card", e);
            return null;
        }
    }

    private TransitCardData readIsoDepCard(IsoDep isoDep, byte[] cardId) {
        Log.d(TAG, "readIsoDepCard: Starting IsoDep card read");
        try {
            isoDep.connect();
            isoDep.setTimeout(5000);
            Log.d(TAG, "readIsoDepCard: Connected to card, timeout=5000ms");
            Log.d(TAG, "readIsoDepCard: Max transceive length = " + isoDep.getMaxTransceiveLength());

            // Detect card type based on card ID and AID
            CardType cardType = detectCardType(cardId, isoDep);
            Log.i(TAG, "readIsoDepCard: Detected card type = " + cardType);

            CardParser parser = null;
            switch (cardType) {
                case TMONEY:
                    parser = new TMoneyParser();
                    break;
                case CASHBEE:
                    parser = new CashbeeParser();
                    break;
                case HANPAY:
                    parser = new HanpayParser();
                    break;
                case RAILPLUS:
                    parser = new RailplusParser();
                    break;
                case MPASS:
                    parser = new MPassParser();
                    break;
                case SEOUL_CITY_PASS:
                    parser = new SeoulCityPassParser();
                    break;
                case KOREA_TOUR_CARD:
                    parser = new KoreaTourCardParser();
                    break;
                case EZL:
                    parser = new EZLParser();
                    break;
            }

            TransitCardData result = null;
            if (parser != null) {
                Log.d(TAG, "readIsoDepCard: Using parser = " + parser.getClass().getSimpleName());
                result = parser.parse(isoDep, cardId);
                Log.d(TAG, "readIsoDepCard: Parse result = " + (result != null ? "success" : "null"));
            } else {
                Log.w(TAG, "readIsoDepCard: No parser available for card type " + cardType);
            }

            isoDep.close();
            Log.d(TAG, "readIsoDepCard: Connection closed");
            return result;
        } catch (Exception e) {
            Log.e(TAG, "readIsoDepCard: Error reading IsoDep card", e);
            try {
                isoDep.close();
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    private TransitCardData readNfcACard(NfcA nfcA, byte[] cardId) {
        try {
            nfcA.connect();

            // Create basic card data
            CardType cardType = detectCardTypeFromId(cardId);
            String cardNumber = bytesToHex(cardId);

            nfcA.close();

            return new TransitCardData(cardType, cardNumber, 0);
        } catch (Exception e) {
            Log.e(TAG, "Error reading NfcA card", e);
            try {
                nfcA.close();
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    private CardType detectCardType(byte[] cardId, IsoDep isoDep) {
        Log.d(TAG, "detectCardType: Starting card type detection");
        Log.d(TAG, "detectCardType: Card ID = " + bytesToHex(cardId));

        // Try to select common Korean transit card AIDs
        // T-money 계열 (T-money, 캐시비, 레일플러스 등 대부분의 한국 교통카드)
        byte[] tmoneyAID = new byte[]{
                (byte) 0xD4, (byte) 0x10, (byte) 0x00, (byte) 0x00,
                (byte) 0x03, (byte) 0x00, (byte) 0x01
        };

        byte[] cashbeeAID = new byte[]{
                (byte) 0xD4, (byte) 0x10, (byte) 0x00, (byte) 0x00,
                (byte) 0x03, (byte) 0x00, (byte) 0x02
        };

        byte[] railplusAID = new byte[]{
                (byte) 0xD4, (byte) 0x10, (byte) 0x00, (byte) 0x00,
                (byte) 0x03, (byte) 0x00, (byte) 0x03
        };

        byte[] mpassAID = new byte[]{
                (byte) 0xD4, (byte) 0x10, (byte) 0x00, (byte) 0x00,
                (byte) 0x03, (byte) 0x00, (byte) 0x04
        };

        // EZL (이즐) - 코레일 교통카드, 여러 AID 시도
        byte[] ezlAID1 = new byte[]{
                (byte) 0xD4, (byte) 0x10, (byte) 0x00, (byte) 0x00,
                (byte) 0x03, (byte) 0x00, (byte) 0x05
        };

        // EZL 대체 AID (T-money 호환)
        byte[] ezlAID2 = new byte[]{
                (byte) 0xD4, (byte) 0x10, (byte) 0x00, (byte) 0x00,
                (byte) 0x03, (byte) 0x00, (byte) 0x06
        };

        // 먼저 EZL 시도 (EZL 카드가 T-money보다 먼저 감지되도록)
        Log.d(TAG, "detectCardType: Trying EZL AID 1...");
        try {
            byte[] response = selectAID(isoDep, ezlAID1);
            if (response != null && response.length >= 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;
                Log.d(TAG, "detectCardType: EZL AID 1 response SW=" + String.format("%02X%02X", sw1, sw2));
                if (sw1 == 0x90 && sw2 == 0x00) {
                    Log.i(TAG, "detectCardType: EZL card detected (AID 1)");
                    return CardType.EZL;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "detectCardType: EZL AID 1 failed: " + e.getMessage());
        }

        Log.d(TAG, "detectCardType: Trying EZL AID 2...");
        try {
            byte[] response = selectAID(isoDep, ezlAID2);
            if (response != null && response.length >= 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;
                Log.d(TAG, "detectCardType: EZL AID 2 response SW=" + String.format("%02X%02X", sw1, sw2));
                if (sw1 == 0x90 && sw2 == 0x00) {
                    Log.i(TAG, "detectCardType: EZL card detected (AID 2)");
                    return CardType.EZL;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "detectCardType: EZL AID 2 failed: " + e.getMessage());
        }

        // Try T-money (also used by Seoul City Pass and Korea Tour Card)
        Log.d(TAG, "detectCardType: Trying T-money AID...");
        try {
            byte[] response = selectAID(isoDep, tmoneyAID);
            if (response != null && response.length >= 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;
                Log.d(TAG, "detectCardType: T-money response SW=" + String.format("%02X%02X", sw1, sw2));
                if (sw1 == 0x90 && sw2 == 0x00) {
                    // Check if it's Seoul City Pass or Korea Tour Card
                    CardType specificType = detectTmoneyBasedCard(isoDep, cardId);
                    if (specificType != CardType.UNKNOWN) {
                        return specificType;
                    }
                    Log.i(TAG, "detectCardType: T-money card detected");
                    return CardType.TMONEY;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "detectCardType: T-money failed: " + e.getMessage());
        }

        // Try Cashbee
        Log.d(TAG, "detectCardType: Trying Cashbee AID...");
        try {
            byte[] response = selectAID(isoDep, cashbeeAID);
            if (response != null && response.length >= 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;
                Log.d(TAG, "detectCardType: Cashbee response SW=" + String.format("%02X%02X", sw1, sw2));
                if (sw1 == 0x90 && sw2 == 0x00) {
                    Log.i(TAG, "detectCardType: Cashbee card detected");
                    return CardType.CASHBEE;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "detectCardType: Cashbee failed: " + e.getMessage());
        }

        // Try Rail+
        Log.d(TAG, "detectCardType: Trying Rail+ AID...");
        try {
            byte[] response = selectAID(isoDep, railplusAID);
            if (response != null && response.length >= 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;
                Log.d(TAG, "detectCardType: Rail+ response SW=" + String.format("%02X%02X", sw1, sw2));
                if (sw1 == 0x90 && sw2 == 0x00) {
                    Log.i(TAG, "detectCardType: Rail+ card detected");
                    return CardType.RAILPLUS;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "detectCardType: Rail+ failed: " + e.getMessage());
        }

        // Try M Pass
        Log.d(TAG, "detectCardType: Trying M Pass AID...");
        try {
            byte[] response = selectAID(isoDep, mpassAID);
            if (response != null && response.length >= 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;
                Log.d(TAG, "detectCardType: M Pass response SW=" + String.format("%02X%02X", sw1, sw2));
                if (sw1 == 0x90 && sw2 == 0x00) {
                    Log.i(TAG, "detectCardType: M Pass card detected");
                    return CardType.MPASS;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "detectCardType: M Pass failed: " + e.getMessage());
        }

        // Fallback to ID-based detection
        Log.w(TAG, "detectCardType: No AID matched, falling back to ID-based detection");
        return detectCardTypeFromId(cardId);
    }

    private CardType detectTmoneyBasedCard(IsoDep isoDep, byte[] cardId) {
        // Seoul City Pass and Korea Tour Card use T-money platform
        // but have distinctive characteristics

        // This would need actual card testing to implement proper detection
        // For now, return UNKNOWN to default to TMONEY
        return CardType.UNKNOWN;
    }

    private CardType detectCardTypeFromId(byte[] cardId) {
        // This is a simplified detection based on common patterns
        // Real implementation would need more sophisticated detection
        return CardType.UNKNOWN;
    }

    private byte[] selectAID(IsoDep isoDep, byte[] aid) {
        byte[] selectCommand = new byte[6 + aid.length];
        selectCommand[0] = 0x00; // CLA
        selectCommand[1] = (byte) 0xA4; // INS (SELECT)
        selectCommand[2] = 0x04; // P1
        selectCommand[3] = 0x00; // P2
        selectCommand[4] = (byte) aid.length; // Lc
        System.arraycopy(aid, 0, selectCommand, 5, aid.length);
        selectCommand[selectCommand.length - 1] = 0x00; // Le

        try {
            Log.d(TAG, "selectAID: Sending SELECT command for AID: " + bytesToHex(aid));
            Log.d(TAG, "selectAID: Command = " + bytesToHex(selectCommand));
            byte[] response = isoDep.transceive(selectCommand);
            Log.d(TAG, "selectAID: Response = " + bytesToHex(response));
            return response;
        } catch (Exception e) {
            Log.e(TAG, "selectAID: Error selecting AID " + bytesToHex(aid), e);
            return null;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            result.append(String.format("%02X", b));
        }
        return result.toString();
    }
}
