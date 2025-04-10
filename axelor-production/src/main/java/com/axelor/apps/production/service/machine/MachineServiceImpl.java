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
package com.axelor.apps.production.service.machine;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.DayPlanning;
import com.axelor.apps.base.db.EventsPlanning;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.base.service.dayplanning.DayPlanningService;
import com.axelor.apps.base.service.weeklyplanning.WeeklyPlanningService;
import com.axelor.apps.production.db.Machine;
import com.axelor.apps.production.db.OperationOrder;
import com.axelor.apps.production.db.WorkCenter;
import com.axelor.apps.production.db.repo.ManufOrderRepository;
import com.axelor.apps.production.db.repo.OperationOrderRepository;
import com.axelor.apps.production.exceptions.ProductionExceptionMessage;
import com.axelor.apps.production.model.machine.MachineTimeSlot;
import com.axelor.i18n.I18n;
import com.axelor.utils.helpers.date.DurationHelper;
import com.google.inject.Inject;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

public class MachineServiceImpl implements MachineService {

  public static final int MAX_LOOP_CALL = 1000;
  public static final int MAX_RECURSIVE_CALL = 200;
  protected OperationOrderRepository operationOrderRepository;
  protected WeeklyPlanningService weeklyPlanningService;
  protected DayPlanningService dayPlanningService;

  @Inject
  public MachineServiceImpl(
      OperationOrderRepository operationOrderRepository,
      WeeklyPlanningService weeklyPlanningService,
      DayPlanningService dayPlanningService) {
    this.operationOrderRepository = operationOrderRepository;
    this.weeklyPlanningService = weeklyPlanningService;
    this.dayPlanningService = dayPlanningService;
  }

  @Override
  public MachineTimeSlot getClosestAvailableTimeSlotFrom(
      Machine machine,
      LocalDateTime startDateT,
      LocalDateTime endDateT,
      OperationOrder operationOrder)
      throws AxelorException {

    return getClosestAvailableTimeSlotFrom(
        machine,
        startDateT,
        endDateT,
        operationOrder,
        DurationHelper.getSecondsDuration(Duration.between(startDateT, endDateT)),
        false,
        0);
  }

  @Override
  public MachineTimeSlot getClosestTimeSlotFrom(
      Machine machine,
      LocalDateTime startDateT,
      LocalDateTime endDateT,
      OperationOrder operationOrder)
      throws AxelorException {

    return getClosestAvailableTimeSlotFrom(
        machine,
        startDateT,
        endDateT,
        operationOrder,
        DurationHelper.getSecondsDuration(Duration.between(startDateT, endDateT)),
        true,
        0);
  }

  @SuppressWarnings("unchecked")
  protected MachineTimeSlot getClosestAvailableTimeSlotFrom(
      Machine machine,
      LocalDateTime startDateT,
      LocalDateTime endDateT,
      OperationOrder operationOrder,
      long initialDuration,
      boolean ignoreConcurrency,
      int loopNb)
      throws AxelorException {

    EventsPlanning planning = machine.getPublicHolidayEventsPlanning();

    // If startDate is not available because of planning
    // Then we try for the next day
    LocalDateTime nextDayDateT = startDateT.plusDays(1).with(LocalTime.MIN);
    LocalDateTime plannedStartDateT = null;
    LocalDateTime plannedEndDateT = null;

    if (planning != null
        && planning.getEventsPlanningLineList() != null
        && planning.getEventsPlanningLineList().stream()
            .anyMatch(epl -> epl.getDate().equals(startDateT.toLocalDate()))) {

      return getClosestAvailableTimeSlotFrom(
          machine,
          nextDayDateT,
          nextDayDateT.plusSeconds(initialDuration),
          operationOrder,
          initialDuration,
          ignoreConcurrency,
          loopNb);
    }

    if (machine.getWeeklyPlanning() != null) {
      // Planning on date at startDateT
      DayPlanning dayPlanning =
          weeklyPlanningService.findDayPlanning(
              machine.getWeeklyPlanning(), startDateT.toLocalDate());
      Optional<LocalDateTime> allowedStartDateTPeriodAt =
          dayPlanningService.getAllowedStartDateTPeriodAt(dayPlanning, startDateT);

      if (allowedStartDateTPeriodAt.isEmpty()) {
        throw new AxelorException(
            TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
            I18n.get(ProductionExceptionMessage.OPERATION_ORDER_NO_PERIOD_FOUND_FOR_PLAN_DATES),
            operationOrder.getName());
      }

      plannedStartDateT = allowedStartDateTPeriodAt.get();
      plannedEndDateT = plannedStartDateT.plusSeconds(initialDuration);

      // Must end in a existing period.
      plannedEndDateT =
          dayPlanningService.getAllowedStartDateTPeriodAt(dayPlanning, plannedEndDateT).get();
      // Void duration is time where machine is not used (not in any period)
      long remainingTime = 0l;
      int counter = 0;
      do {

        long voidDuration =
            dayPlanningService.computeVoidDurationBetween(
                dayPlanning, plannedStartDateT, plannedEndDateT);

        remainingTime =
            initialDuration
                - DurationHelper.getSecondsDuration(
                    Duration.between(plannedStartDateT, plannedEndDateT)
                        .minusSeconds(voidDuration));

        // So the time 'spent' must be reported
        plannedEndDateT = plannedEndDateT.plusSeconds(remainingTime);

        // And of course it must end in a existing period.
        plannedEndDateT =
            dayPlanningService.getAllowedStartDateTPeriodAt(dayPlanning, plannedEndDateT).get();

        counter++;

      } while (remainingTime > 0 && counter < MAX_LOOP_CALL);

      if (counter == MAX_LOOP_CALL) {
        throw new AxelorException(
            TraceBackRepository.CATEGORY_INCONSISTENCY,
            ProductionExceptionMessage.TOO_MANY_CALL_GETTING_END_DATE);
      }

    } else {
      // The machine does not have weekly planning so dates are ok for now.
      plannedStartDateT = startDateT;
      plannedEndDateT = endDateT;
    }

    if (ignoreConcurrency) {
      return new MachineTimeSlot(plannedStartDateT, plannedEndDateT);
    }

    return getClosestAvailableMachineTimeSlot(
        machine, operationOrder, initialDuration, plannedStartDateT, plannedEndDateT, loopNb);
  }

  protected MachineTimeSlot getClosestAvailableMachineTimeSlot(
      Machine machine,
      OperationOrder operationOrder,
      long initialDuration,
      LocalDateTime plannedStartDateT,
      LocalDateTime plannedEndDateT,
      int loopNb)
      throws AxelorException {
    long timeBeforeNextOperation =
        Optional.ofNullable(operationOrder.getWorkCenter())
            .map(WorkCenter::getTimeBeforeNextOperation)
            .orElse(0l);
    // Must check if dates are occupied by other operation orders
    // The first one of the list will be the last to finish

    if (loopNb >= MAX_RECURSIVE_CALL) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          ProductionExceptionMessage.TOO_MANY_CALL_GETTING_TIME_SLOT,
          operationOrder.getName());
    }

    List<OperationOrder> concurrentOperationOrders =
        operationOrderRepository
            .all()
            .filter(
                "self.machine = :machine"
                    + " AND ((self.plannedStartDateT <= :startDate AND self.plannedEndDateT > :startDateWithTime)"
                    + " OR (self.plannedStartDateT <= :endDate AND self.plannedEndDateT > :endDateWithTime)"
                    + " OR (self.plannedStartDateT >= :startDate AND self.plannedEndDateT <= :endDateWithTime))"
                    + " AND (self.manufOrder.statusSelect != :cancelled AND self.manufOrder.statusSelect != :finished)"
                    + " AND self.id != :operationOrderId"
                    + " AND self.outsourcing = false")
            .bind("startDate", plannedStartDateT)
            .bind("endDate", plannedEndDateT)
            .bind("startDateWithTime", plannedStartDateT.minusSeconds(timeBeforeNextOperation))
            .bind("endDateWithTime", plannedEndDateT.minusSeconds(timeBeforeNextOperation))
            .bind("machine", machine)
            .bind("cancelled", ManufOrderRepository.STATUS_CANCELED)
            .bind("finished", ManufOrderRepository.STATUS_FINISHED)
            .bind("operationOrderId", operationOrder.getId())
            .order("-plannedEndDateT")
            .fetch();

    if (concurrentOperationOrders.isEmpty()) {
      return new MachineTimeSlot(plannedStartDateT, plannedEndDateT);
    } else {
      OperationOrder lastOperationOrder = concurrentOperationOrders.get(0);

      if (timeBeforeNextOperation == 0 && initialDuration == 0) {
        throw new AxelorException(
            TraceBackRepository.CATEGORY_INCONSISTENCY,
            I18n.get(
                ProductionExceptionMessage.MANUF_ORDER_CANT_COMPUTE_NEXT_SLOT_WITH_CURRENT_CONFIG),
            operationOrder.getName());
      }

      return getClosestAvailableTimeSlotFrom(
          machine,
          lastOperationOrder.getPlannedEndDateT().plusSeconds(timeBeforeNextOperation),
          lastOperationOrder
              .getPlannedEndDateT()
              .plusSeconds(timeBeforeNextOperation + initialDuration),
          operationOrder,
          initialDuration,
          false,
          loopNb + 1);
    }
  }

  @Override
  public MachineTimeSlot getFurthestAvailableTimeSlotFrom(
      Machine machine,
      LocalDateTime startDateT,
      LocalDateTime endDateT,
      OperationOrder operationOrder)
      throws AxelorException {

    return getFurthestAvailableTimeSlotFrom(
        machine,
        startDateT,
        endDateT,
        operationOrder,
        DurationHelper.getSecondsDuration(Duration.between(startDateT, endDateT)),
        false,
        0);
  }

  @Override
  public MachineTimeSlot getFurthestTimeSlotFrom(
      Machine machine,
      LocalDateTime startDateT,
      LocalDateTime endDateT,
      OperationOrder operationOrder)
      throws AxelorException {

    return getFurthestAvailableTimeSlotFrom(
        machine,
        startDateT,
        endDateT,
        operationOrder,
        DurationHelper.getSecondsDuration(Duration.between(startDateT, endDateT)),
        true,
        0);
  }

  @SuppressWarnings("unchecked")
  protected MachineTimeSlot getFurthestAvailableTimeSlotFrom(
      Machine machine,
      LocalDateTime startDateT,
      LocalDateTime endDateT,
      OperationOrder operationOrder,
      long initialDuration,
      boolean ignoreConcurrency,
      int loopNb)
      throws AxelorException {

    EventsPlanning planning = machine.getPublicHolidayEventsPlanning();

    if (planning != null
        && planning.getEventsPlanningLineList() != null
        && planning.getEventsPlanningLineList().stream()
            .anyMatch(epl -> epl.getDate().equals(endDateT.toLocalDate()))) {

      // If endDate is not available because of planning
      // Then we try for the previous day
      LocalDateTime previousDayDateT = endDateT.plusDays(1).with(LocalTime.MIN);

      return getFurthestAvailableTimeSlotFrom(
          machine,
          previousDayDateT.minusSeconds(initialDuration),
          previousDayDateT,
          operationOrder,
          initialDuration,
          ignoreConcurrency,
          loopNb);
    }

    LocalDateTime plannedStartDateT = null;
    LocalDateTime plannedEndDateT = null;

    if (machine.getWeeklyPlanning() != null) {
      // Planning on date at startDateT
      DayPlanning dayPlanning =
          weeklyPlanningService.findDayPlanning(
              machine.getWeeklyPlanning(), startDateT.toLocalDate());
      Optional<LocalDateTime> allowedEndDateTPeriodAt =
          dayPlanningService.getAllowedEndDateTPeriodAt(dayPlanning, endDateT);

      if (allowedEndDateTPeriodAt.isEmpty()) {
        throw new AxelorException(
            TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
            I18n.get(ProductionExceptionMessage.OPERATION_ORDER_NO_PERIOD_FOUND_FOR_PLAN_DATES),
            operationOrder.getName());
      }

      plannedEndDateT = allowedEndDateTPeriodAt.get();
      plannedStartDateT = plannedEndDateT.minusSeconds(initialDuration);

      // Must end in an existing period.
      plannedStartDateT =
          dayPlanningService.getAllowedEndDateTPeriodAt(dayPlanning, plannedStartDateT).get();
      // Void duration is time when machine is not used (not in any period)

      long remainingTime = 0l;
      int counter = 0;
      do {

        long voidDuration =
            dayPlanningService.computeVoidDurationBetween(
                dayPlanning, plannedStartDateT, plannedEndDateT);

        remainingTime =
            initialDuration
                - DurationHelper.getSecondsDuration(
                    Duration.between(plannedStartDateT, plannedEndDateT)
                        .minusSeconds(voidDuration));
        // So the time 'spent' must be reported
        plannedStartDateT = plannedStartDateT.minusSeconds(remainingTime);

        // And of course it must start also in an existing period.
        plannedStartDateT =
            dayPlanningService.getAllowedEndDateTPeriodAt(dayPlanning, plannedStartDateT).get();

        counter++;
      } while (remainingTime > 0 && counter < MAX_LOOP_CALL);

      if (counter == MAX_LOOP_CALL) {
        throw new AxelorException(
            TraceBackRepository.CATEGORY_INCONSISTENCY,
            ProductionExceptionMessage.TOO_MANY_CALL_GETTING_START_DATE);
      }

    } else {
      // The machine does not have weekly planning so dates are ok for now.
      plannedStartDateT = startDateT;
      plannedEndDateT = endDateT;
    }

    if (ignoreConcurrency) {
      return new MachineTimeSlot(plannedStartDateT, plannedEndDateT);
    }
    return getFurthestAvailableMachineTimeSlot(
        machine, operationOrder, initialDuration, plannedStartDateT, plannedEndDateT, loopNb);
  }

  protected MachineTimeSlot getFurthestAvailableMachineTimeSlot(
      Machine machine,
      OperationOrder operationOrder,
      long initialDuration,
      LocalDateTime plannedStartDateT,
      LocalDateTime plannedEndDateT,
      int loopNb)
      throws AxelorException {

    if (loopNb >= MAX_RECURSIVE_CALL) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          ProductionExceptionMessage.TOO_MANY_CALL_GETTING_TIME_SLOT,
          operationOrder.getName());
    }
    long timeBeforeNextOperation =
        Optional.ofNullable(operationOrder.getWorkCenter())
            .map(WorkCenter::getTimeBeforeNextOperation)
            .orElse(0l);
    // Must check if dates are occupied by other operation orders
    // The first one of the list will be the first to start
    List<OperationOrder> concurrentOperationOrders =
        operationOrderRepository
            .all()
            .filter(
                "self.machine = :machine"
                    + " AND ((self.plannedStartDateT <= :startDate AND self.plannedEndDateT > :startDateWithTime)"
                    + " OR (self.plannedStartDateT < :endDate AND self.plannedEndDateT > :endDateWithTime)"
                    + " OR (self.plannedStartDateT >= :startDate AND self.plannedEndDateT <= :endDateWithTime))"
                    + " AND (self.manufOrder.statusSelect != :cancelled AND self.manufOrder.statusSelect != :finished)"
                    + " AND self.id != :operationOrderId"
                    + " AND self.outsourcing = false")
            .bind("startDate", plannedStartDateT)
            .bind("endDate", plannedEndDateT)
            .bind("startDateWithTime", plannedStartDateT.minusSeconds(timeBeforeNextOperation))
            .bind("endDateWithTime", plannedEndDateT.minusSeconds(timeBeforeNextOperation))
            .bind("machine", machine)
            .bind("cancelled", ManufOrderRepository.STATUS_CANCELED)
            .bind("finished", ManufOrderRepository.STATUS_FINISHED)
            .bind("operationOrderId", operationOrder.getId())
            .order("plannedStartDateT")
            .fetch();

    if (concurrentOperationOrders.isEmpty()) {
      return new MachineTimeSlot(plannedStartDateT, plannedEndDateT);
    } else {
      OperationOrder firstOperationOrder = concurrentOperationOrders.get(0);

      // Can not compute next slot with concurrency if these values are 0
      if (timeBeforeNextOperation == 0 && initialDuration == 0) {
        throw new AxelorException(
            TraceBackRepository.CATEGORY_INCONSISTENCY,
            I18n.get(
                ProductionExceptionMessage.MANUF_ORDER_CANT_COMPUTE_NEXT_SLOT_WITH_CURRENT_CONFIG),
            operationOrder.getName());
      }

      return getFurthestAvailableTimeSlotFrom(
          machine,
          firstOperationOrder
              .getPlannedStartDateT()
              .minusSeconds(initialDuration + timeBeforeNextOperation),
          firstOperationOrder.getPlannedStartDateT().minusSeconds(timeBeforeNextOperation),
          operationOrder,
          initialDuration,
          false,
          loopNb + 1);
    }
  }
}
