package com.transitcard.reader;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

import com.transitcard.reader.Transaction;
import com.transitcard.reader.TransactionTypeConverter;

/**
 * AppDatabase - Room Database 메인 클래스
 *
 * 역할:
 * - 데이터베이스 생성 및 관리 (SQLite 기반)
 * - 테이블(Entity) 정의
 * - DAO 제공
 * - Singleton 패턴으로 앱 전체에서 1개만 사용
 *
 * Singleton이란?
 * - 앱 전체에서 Database 객체를 1개만 생성
 * - 메모리 절약, 데이터 일관성 유지
 *
 * 데이터베이스 정보:
 * - 파일명: transit_card_database
 * - 버전: 1
 * - 테이블: cards (카드 정보), transactions (거래 내역)
 * - 저장 위치: /data/data/com.transitcard.reader/databases/transit_card_database
 */
@Database(
        entities = {CardEntity.class, Transaction.class},  // 포함할 Entity(테이블) 리스트
        version = 1,                                       // DB 버전 (스키마 변경 시 증가)
        exportSchema = false                               // 스키마 자동 export 안 함
)
@TypeConverters({TransactionTypeConverter.class})      // enum 변환기 등록
public abstract class AppDatabase extends RoomDatabase {

    /**
     * Singleton 인스턴스
     * volatile: 멀티스레드에서 안전하게 사용 (메모리 가시성 보장)
     */
    private static volatile AppDatabase INSTANCE;

    /**
     * CardDao 제공
     * Room이 자동으로 구현체 생성
     *
     * 반환값:
     * - CardDao 인터페이스의 구현체
     * - 실제로는 CardDao_Impl 클래스 (Room이 자동 생성)
     */
    public abstract CardDao cardDao();

    /**
     * Database 인스턴스 가져오기 (Singleton)
     *
     * @param context Application Context (Activity의 this 아님!)
     * @return AppDatabase 인스턴스 (앱에서 단 1개)
     *
     * 동작:
     * 1. INSTANCE가 null이면 (처음 호출 시)
     *    → 새로운 Database 생성
     * 2. INSTANCE가 있으면
     *    → 기존 인스턴스 반환
     *
     * Thread-Safe:
     * - synchronized 블록으로 동시 접근 방지
     * - Double-Checked Locking 패턴 사용
     */
    public static AppDatabase getInstance(Context context) {
        // 첫 번째 체크 (빠른 확인, 락 없이)
        if (INSTANCE == null) {
            // 락 획득 (동시에 여러 스레드가 접근 방지)
            synchronized (AppDatabase.class) {
                // 두 번째 체크 (정확한 확인, 락 안에서)
                if (INSTANCE == null) {
                    // Database 생성
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),  // Application Context 사용 (메모리 누수 방지)
                            AppDatabase.class,                // Database 클래스
                            "transit_card_database"           // DB 파일명
                    ).build();

                    // 이 순간 SQLite 파일이 생성됨
                    // CREATE TABLE cards (...);
                    // CREATE TABLE transactions (...);
                }
            }
        }
        return INSTANCE;
    }
}

/*
 * ===== 사용 예시 =====
 *
 * // MainActivity에서
 * public class MainActivity extends AppCompatActivity {
 *     private AppDatabase database;
 *     private CardDao cardDao;
 *
 *     @Override
 *     protected void onCreate(Bundle savedInstanceState) {
 *         super.onCreate(savedInstanceState);
 *
 *         // 1. Database 인스턴스 가져오기
 *         database = AppDatabase.getInstance(this);
 *
 *         // 2. DAO 가져오기
 *         cardDao = database.cardDao();
 *
 *         // 3. 데이터베이스 작업 (백그라운드 스레드)
 *         new Thread(() -> {
 *             CardEntity card = new CardEntity("1234567890", "티머니", 50000);
 *             cardDao.insertCard(card);
 *         }).start();
 *     }
 * }
 *
 *
 * ===== Database 구조 =====
 *
 * transit_card_database
 * │
 * ├── cards 테이블
 * │   ├── id (INTEGER PRIMARY KEY AUTOINCREMENT)
 * │   ├── cardNumber (TEXT)
 * │   ├── cardType (TEXT)
 * │   ├── balance (INTEGER)
 * │   └── lastUpdated (INTEGER)
 * │
 * └── transactions 테이블
 *     ├── id (INTEGER PRIMARY KEY AUTOINCREMENT)
 *     ├── cardId (INTEGER, FOREIGN KEY → cards.id)
 *     ├── date (TEXT)
 *     ├── location (TEXT)
 *     ├── amount (INTEGER)
 *     ├── balanceAfter (INTEGER)
 *     ├── transactionType (TEXT)
 *     └── timestamp (INTEGER)
 *
 *
 * ===== Singleton 패턴 =====
 *
 * getInstance() 여러 번 호출해도:
 *
 * AppDatabase db1 = AppDatabase.getInstance(context);
 * AppDatabase db2 = AppDatabase.getInstance(context);
 * AppDatabase db3 = AppDatabase.getInstance(context);
 *
 * db1 == db2 == db3  // true (모두 같은 인스턴스)
 *
 * 장점:
 * - 메모리 절약 (1개만 생성)
 * - Connection 관리 효율적
 * - 데이터 일관성 보장
 *
 *
 * ===== Double-Checked Locking =====
 *
 * if (INSTANCE == null) {           // 1차 체크 (빠름)
 *     synchronized (...) {          // 락 획득 (느림)
 *         if (INSTANCE == null) {   // 2차 체크 (정확)
 *             INSTANCE = ...;
 *         }
 *     }
 * }
 *
 * 왜 2번 체크?
 * - 1차: 이미 생성되었으면 락 없이 빠르게 반환
 * - 2차: 락 안에서 다시 확인 (Thread-Safe)
 *
 *
 * ===== 버전 관리 =====
 *
 * @Database(version = 1)  ← 현재 버전
 *
 * 스키마 변경 시:
 * 1. version = 2로 증가
 * 2. Migration 코드 추가
 *
 * 예: balance 컬럼 추가
 *
 * static final Migration MIGRATION_1_2 = new Migration(1, 2) {
 *     @Override
 *     public void migrate(SupportSQLiteDatabase db) {
 *         db.execSQL("ALTER TABLE cards ADD COLUMN balance INTEGER DEFAULT 0");
 *     }
 * };
 *
 * Room.databaseBuilder(...)
 *     .addMigrations(MIGRATION_1_2)
 *     .build();
 *
 *
 * ===== TypeConverters =====
 *
 * @TypeConverters({TransactionTypeConverter.class})
 *
 * 역할:
 * - Room이 지원하지 않는 타입(enum) 변환
 * - TransactionType enum ↔ String 변환
 *
 * TransactionType.USE → "USE" (DB 저장)
 * "USE" → TransactionType.USE (DB 읽기)
 */