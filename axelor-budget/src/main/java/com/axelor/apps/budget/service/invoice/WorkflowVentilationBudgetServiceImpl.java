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
package com.axelor.apps.budget.service.invoice;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoicePaymentRepository;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.service.app.AppAccountService;
import com.axelor.apps.account.service.config.AccountConfigService;
import com.axelor.apps.account.service.invoice.InvoiceFinancialDiscountService;
import com.axelor.apps.account.service.invoice.InvoiceService;
import com.axelor.apps.account.service.invoice.InvoiceTermService;
import com.axelor.apps.account.service.payment.invoice.payment.InvoicePaymentCreateService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.service.UnitConversionService;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.budget.service.AppBudgetService;
import com.axelor.apps.businessproject.db.repo.InvoicingProjectRepository;
import com.axelor.apps.businessproject.service.WorkflowVentilationProjectServiceImpl;
import com.axelor.apps.contract.service.ContractVersionService;
import com.axelor.apps.hr.db.repo.TimesheetLineRepository;
import com.axelor.apps.purchase.db.repo.PurchaseOrderRepository;
import com.axelor.apps.sale.db.repo.SaleOrderRepository;
import com.axelor.apps.stock.db.repo.StockMoveLineRepository;
import com.axelor.apps.supplychain.service.AccountingSituationSupplychainService;
import com.axelor.apps.supplychain.service.PurchaseOrderInvoiceService;
import com.axelor.apps.supplychain.service.StockMoveInvoiceService;
import com.axelor.apps.supplychain.service.app.AppSupplychainService;
import com.axelor.apps.supplychain.service.config.SupplyChainConfigService;
import com.axelor.apps.supplychain.service.saleorder.SaleOrderInvoiceService;
import com.axelor.studio.db.AppBudget;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;

public class WorkflowVentilationBudgetServiceImpl extends WorkflowVentilationProjectServiceImpl {

  protected AppBudgetService appBudgetService;
  protected BudgetInvoiceService budgetInvoiceService;

  @Inject
  public WorkflowVentilationBudgetServiceImpl(
      AccountConfigService accountConfigService,
      InvoicePaymentRepository invoicePaymentRepo,
      InvoicePaymentCreateService invoicePaymentCreateService,
      InvoiceService invoiceService,
      SaleOrderInvoiceService saleOrderInvoiceService,
      PurchaseOrderInvoiceService purchaseOrderInvoiceService,
      SaleOrderRepository saleOrderRepository,
      PurchaseOrderRepository purchaseOrderRepository,
      AccountingSituationSupplychainService accountingSituationSupplychainService,
      AppSupplychainService appSupplychainService,
      InvoicingProjectRepository invoicingProjectRepo,
      TimesheetLineRepository timesheetLineRepo,
      StockMoveInvoiceService stockMoveInvoiceService,
      UnitConversionService unitConversionService,
      AppBaseService appBaseService,
      SupplyChainConfigService supplyChainConfigService,
      StockMoveLineRepository stockMoveLineRepository,
      AppAccountService appAccountService,
      InvoiceFinancialDiscountService invoiceFinancialDiscountService,
      InvoiceTermService invoiceTermService,
      AppBudgetService appBudgetService,
      BudgetInvoiceService budgetInvoiceService,
      ContractVersionService contractVersionService) {
    super(
        accountConfigService,
        invoicePaymentRepo,
        invoicePaymentCreateService,
        invoiceService,
        saleOrderInvoiceService,
        purchaseOrderInvoiceService,
        saleOrderRepository,
        purchaseOrderRepository,
        accountingSituationSupplychainService,
        appSupplychainService,
        invoicingProjectRepo,
        timesheetLineRepo,
        stockMoveInvoiceService,
        unitConversionService,
        appBaseService,
        supplyChainConfigService,
        stockMoveLineRepository,
        appAccountService,
        invoiceFinancialDiscountService,
        invoiceTermService,
        contractVersionService);
    this.appBudgetService = appBudgetService;
    this.budgetInvoiceService = budgetInvoiceService;
  }

  @Override
  @Transactional(rollbackOn = {Exception.class})
  public void afterVentilation(Invoice invoice) throws AxelorException {
    super.afterVentilation(invoice);
    AppBudget appBudget = appBudgetService.getAppBudget();
    if (appBudget != null) {
      if ((invoice.getOperationTypeSelect() == InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE
          || invoice.getOperationTypeSelect() == InvoiceRepository.OPERATION_TYPE_CLIENT_SALE)) {
        if (!appBudget.getManageMultiBudget()) {
          budgetInvoiceService.generateBudgetDistribution(invoice);
        }
      }

      budgetInvoiceService.updateBudgetLinesFromInvoice(invoice);
    }
  }
}
