/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2005-2025 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.axelor.apps.bankpayment.service.bankreconciliation;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountType;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.repo.AccountRepository;
import com.axelor.apps.account.db.repo.JournalTypeRepository;
import com.axelor.apps.account.db.repo.MoveLineRepository;
import com.axelor.apps.bankpayment.db.BankReconciliation;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.service.BankDetailsService;
import com.axelor.common.StringUtils;
import com.axelor.utils.helpers.StringHelper;
import com.google.inject.Inject;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.collections.CollectionUtils;

public class BankReconciliationDomainServiceImpl implements BankReconciliationDomainService {

  protected BankDetailsService bankDetailsService;
  protected BankReconciliationAccountService bankReconciliationAccountService;
  protected MoveLineRepository moveLineRepository;
  protected BankReconciliationQueryService bankReconciliationQueryService;

  @Inject
  public BankReconciliationDomainServiceImpl(
      BankDetailsService bankDetailsService,
      BankReconciliationAccountService bankReconciliationAccountService,
      MoveLineRepository moveLineRepository,
      BankReconciliationQueryService bankReconciliationQueryService) {
    this.bankDetailsService = bankDetailsService;
    this.bankReconciliationAccountService = bankReconciliationAccountService;
    this.moveLineRepository = moveLineRepository;
    this.bankReconciliationQueryService = bankReconciliationQueryService;
  }

  @Override
  public String getDomainForWizard(
      BankReconciliation bankReconciliation,
      BigDecimal bankStatementCredit,
      BigDecimal bankStatementDebit)
      throws AxelorException {
    String query =
        getMultipleReconcileQuery(bankReconciliation, bankStatementCredit, bankStatementDebit);
    List<MoveLine> authorizedMoveLines =
        moveLineRepository
            .all()
            .filter(query)
            .bind(bankReconciliationQueryService.getBindRequestMoveLine(bankReconciliation))
            .fetch();

    return "self.id in (" + StringHelper.getIdListString(authorizedMoveLines) + ")";
  }

  protected String getMultipleReconcileQuery(
      BankReconciliation bankReconciliation,
      BigDecimal bankStatementCredit,
      BigDecimal bankStatementDebit) {
    String query = "";

    if (bankReconciliation != null
        && bankReconciliation.getCompany() != null
        && bankStatementCredit != null
        && bankStatementDebit != null) {

      query = bankReconciliationQueryService.getRequestMoveLines();

      if (bankReconciliation.getJournal() == null) {
        query =
            query.concat(
                " AND self.move.journal.journalType.technicalTypeSelect = "
                    + JournalTypeRepository.TECHNICAL_TYPE_SELECT_TREASURY);
      }

      if (bankStatementCredit.signum() > 0) {
        query = query.concat(" AND self.debit > 0");
      }

      if (bankStatementDebit.signum() > 0) {
        query = query.concat(" AND self.credit > 0");
      }
    }
    return query;
  }

  @Override
  public String getAccountDomain(BankReconciliation bankReconciliation) {
    if (bankReconciliation != null) {
      String domain = "self.statusSelect = " + AccountRepository.STATUS_ACTIVE;
      if (bankReconciliation.getCompany() != null) {
        domain = domain.concat(" AND self.company.id = " + bankReconciliation.getCompany().getId());
      }
      if (bankReconciliation.getCashAccount() != null) {
        domain = domain.concat(" AND self.id != " + bankReconciliation.getCashAccount().getId());
      }
      if (bankReconciliation.getJournal() != null
          && !CollectionUtils.isEmpty(bankReconciliation.getJournal().getValidAccountTypeSet())) {
        domain =
            domain.concat(
                " AND (self.accountType.id IN "
                    + bankReconciliation.getJournal().getValidAccountTypeSet().stream()
                        .map(AccountType::getId)
                        .map(id -> id.toString())
                        .collect(Collectors.joining("','", "('", "')"))
                        .toString());
      } else {
        domain = domain.concat(" AND (self.accountType.id = 0");
      }
      if (bankReconciliation.getJournal() != null
          && !CollectionUtils.isEmpty(bankReconciliation.getJournal().getValidAccountSet())) {
        domain =
            domain.concat(
                " OR self.id IN "
                    + bankReconciliation.getJournal().getValidAccountSet().stream()
                        .map(Account::getId)
                        .map(id -> id.toString())
                        .collect(Collectors.joining("','", "('", "')"))
                        .toString()
                    + ")");
      } else {
        domain = domain.concat(" OR self.id = 0)");
      }
      return domain;
    }
    return "self.id = 0";
  }

  @Override
  public String getCashAccountDomain(BankReconciliation bankReconciliation) {

    String cashAccountIds = null;
    Set<String> cashAccountIdSet = new HashSet<String>();

    cashAccountIdSet.addAll(
        bankReconciliationAccountService.getAccountManagementCashAccounts(bankReconciliation));

    if (bankReconciliation.getBankDetails().getBankAccount() != null) {
      cashAccountIdSet.add(bankReconciliation.getBankDetails().getBankAccount().getId().toString());
    }

    cashAccountIds = String.join(",", cashAccountIdSet);

    return cashAccountIds;
  }

  @Override
  public String createDomainForMoveLine(BankReconciliation bankReconciliation)
      throws AxelorException {
    String domain = "";
    String idList =
        moveLineRepository
            .all()
            .filter(bankReconciliationQueryService.getRequestMoveLines())
            .bind(bankReconciliationQueryService.getBindRequestMoveLine(bankReconciliation))
            .select("id")
            .fetch(0, 0)
            .stream()
            .map(m -> (Long) m.get("id"))
            .map(String::valueOf)
            .collect(Collectors.joining(","));

    if (StringUtils.isEmpty(idList)) {
      domain = "self.id IN (0)";
    } else {
      domain = "self.id IN (" + idList + ")";
    }
    return domain;
  }

  @Override
  public String getJournalDomain(BankReconciliation bankReconciliation) {

    String journalIds = null;
    Set<String> journalIdSet = new HashSet<String>();

    journalIdSet.addAll(
        bankReconciliationAccountService.getAccountManagementJournals(bankReconciliation));

    if (bankReconciliation.getBankDetails().getJournal() != null) {
      journalIdSet.add(bankReconciliation.getBankDetails().getJournal().getId().toString());
    }

    journalIds = String.join(",", journalIdSet);

    return journalIds;
  }
}
