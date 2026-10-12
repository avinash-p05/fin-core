package com.fincore.ledger;

/**
 * Convention: for every account, balance = SUM(CREDIT) - SUM(DEBIT).
 * Customer accounts are deposits (liabilities of the simulated bank): a CREDIT increases the
 * customer's balance and a DEBIT decreases it.
 */
public enum EntryDirection {
    DEBIT,
    CREDIT
}
