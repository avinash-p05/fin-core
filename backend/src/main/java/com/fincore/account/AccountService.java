package com.fincore.account;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fincore.account.AccountDtos.CreateAccountRequest;
import com.fincore.common.ApiException;
import com.fincore.common.Money;
import com.fincore.common.RowLocks;
import com.fincore.customer.CustomerService;
import com.fincore.ledger.LedgerService;
import com.fincore.ledger.LedgerTransactionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final CustomerService customers;
    private final LedgerService ledger;
    private final RowLocks rowLocks;

    public AccountService(AccountRepository accounts, CustomerService customers, LedgerService ledger,
                          RowLocks rowLocks) {
        this.accounts = accounts;
        this.customers = customers;
        this.ledger = ledger;
        this.rowLocks = rowLocks;
    }

    @Transactional
    public Account create(CreateAccountRequest request) {
        customers.get(request.customerId());
        Account account = accounts.saveAndFlush(Account.openCustomerAccount(request.customerId(), request.displayName().trim()));
        BigDecimal opening = request.openingBalance() == null ? BigDecimal.ZERO : Money.normalize(request.openingBalance());
        if (opening.signum() > 0) {
            // The opening balance is an explicit, balanced ledger transaction: DEBIT the system
            // funding account, CREDIT the new account. Balance and ledger commit together.
            Map<UUID, Account> locked = lockInOrder(Account.SYSTEM_FUNDING_ACCOUNT_ID, account.getId());
            ledger.postTransfer(LedgerTransactionType.OPENING_BALANCE, null, null, "Opening balance",
                    Account.SYSTEM_FUNDING_ACCOUNT_ID, account.getId(), opening, locked);
            return locked.get(account.getId());
        }
        return account;
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return accounts.findById(id).orElseThrow(() -> ApiException.notFound("Account", id));
    }

    @Transactional(readOnly = true)
    public List<Account> list(UUID customerId) {
        return customerId == null
                ? accounts.findByTypeOrderByCreatedAt(AccountType.CUSTOMER)
                : accounts.findByCustomerIdOrderByCreatedAt(customerId);
    }

    @Transactional
    public Account freeze(UUID id) {
        Account account = lock(id);
        requireCustomerAccount(account);
        account.freeze();
        return account;
    }

    @Transactional
    public Account unfreeze(UUID id) {
        Account account = lock(id);
        requireCustomerAccount(account);
        account.unfreeze();
        return account;
    }

    /**
     * Locks the given accounts with {@code SELECT ... FOR UPDATE} in a deterministic order (by the
     * UUID's canonical string form, which matches PostgreSQL's uuid ordering). Two transfers in
     * opposite directions (A→B and B→A) therefore both lock A first and cannot deadlock.
     *
     * <p>Must run inside an existing transaction; the locks are held until it ends.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, Account> lockInOrder(UUID... ids) {
        Map<UUID, Account> locked = new LinkedHashMap<>();
        List.of(ids).stream()
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .forEach(id -> locked.put(id, lock(id)));
        return locked;
    }

    private Account lock(UUID id) {
        return rowLocks.lockForUpdate(Account.class, id).orElseThrow(() -> ApiException.notFound("Account", id));
    }

    private static void requireCustomerAccount(Account account) {
        if (account.getType() != AccountType.CUSTOMER) {
            throw ApiException.unprocessable("SYSTEM_ACCOUNT", "System accounts cannot be modified through this API");
        }
    }
}
