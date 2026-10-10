package com.fincore.account;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fincore.account.AccountDtos.CreateAccountRequest;
import com.fincore.common.ApiException;
import com.fincore.customer.CustomerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final CustomerService customers;

    public AccountService(AccountRepository accounts, CustomerService customers) {
        this.accounts = accounts;
        this.customers = customers;
    }

    @Transactional
    public Account create(CreateAccountRequest request) {
        customers.get(request.customerId());
        if (request.openingBalance() != null && request.openingBalance().signum() > 0) {
            // Opening balances must be posted through the ledger, which is introduced in phase 2.
            throw ApiException.unprocessable("NOT_SUPPORTED", "Opening balances are not supported yet");
        }
        return accounts.save(Account.openCustomerAccount(request.customerId(), request.displayName().trim()));
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
        return accounts.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Account", id));
    }

    private static void requireCustomerAccount(Account account) {
        if (account.getType() != AccountType.CUSTOMER) {
            throw ApiException.unprocessable("SYSTEM_ACCOUNT", "System accounts cannot be modified through this API");
        }
    }
}
