/*
 * Copyright 2012 Eligotech BV.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.eligosource.eventsourced.example.service

import scala.concurrent.stm.Ref

import akka.actor._
import akka.pattern.ask
import akka.dispatch._
import akka.util.duration._
import akka.util.Timeout

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.example.domain._

import scalaz._
import Scalaz._

class InvoiceService(invoicesRef: Ref[Map[String, Invoice]], invoiceComponent: Component) {

  //
  // Consistent reads
  //

  def getInvoicesMap = invoicesRef.single.get
  def getInvoice(invoiceId: String): Option[Invoice] = getInvoicesMap.get(invoiceId)
  def getInvoices: Iterable[Invoice] = getInvoicesMap.values

  def getDraftInvoices = getInvoices.filter(_.isInstanceOf[DraftInvoice])
  def getSentInvoices = getInvoices.filter(_.isInstanceOf[SentInvoice])
  def getPaidInvoices = getInvoices.filter(_.isInstanceOf[PaidInvoice])

  //
  // Updates
  //

  implicit val timeout = Timeout(5 seconds)

  def createInvoice(invoiceId: String): Future[DomainValidation[DraftInvoice]] =
    invoiceComponent.inputProducer ? CreateInvoice(invoiceId) map(_.asInstanceOf[DomainValidation[DraftInvoice]])

  def addInvoiceItem(invoiceId: String, expectedVersion: Option[Long], invoiceItem: InvoiceItem): Future[DomainValidation[DraftInvoice]] =
    invoiceComponent.inputProducer ? AddInvoiceItem(invoiceId, expectedVersion, invoiceItem) map(_.asInstanceOf[DomainValidation[DraftInvoice]])

  def setInvoiceDiscount(invoiceId: String, expectedVersion: Option[Long], discount: BigDecimal): Future[DomainValidation[DraftInvoice]] =
    invoiceComponent.inputProducer ? SetInvoiceDiscount(invoiceId, expectedVersion, discount) map(_.asInstanceOf[DomainValidation[DraftInvoice]])

  def sendInvoiceTo(invoiceId: String, expectedVersion: Option[Long], to: InvoiceAddress): Future[DomainValidation[SentInvoice]] =
    invoiceComponent.inputProducer ? SendInvoiceTo(invoiceId, expectedVersion, to) map(_.asInstanceOf[DomainValidation[SentInvoice]])
}

// -------------------------------------------------------------------------------------------------------------
//  InvoiceProcessor is single writer to invoicesRef, so we can have reads and writes in separate transactions
// -------------------------------------------------------------------------------------------------------------

class InvoiceProcessor(invoicesRef: Ref[Map[String, Invoice]], outputChannels: Map[String, ActorRef]) extends Actor {
  import InvoiceProcessor._

  val listeners = outputChannels("listeners")

  def receive = {
    case msg: Message => msg.event match {
      case CreateInvoice(invoiceId) =>
        process(createInvoice(invoiceId), msg.sender) { invoice =>
          listeners ! msg.copy(event = InvoiceCreated(invoiceId))
        }
      case AddInvoiceItem(invoiceId, expectedVersion, invoiceItem) =>
        process(addInvoiceItem(invoiceId, expectedVersion, invoiceItem), msg.sender) { invoice =>
          listeners ! msg.copy(event = InvoiceItemAdded(invoiceId, invoiceItem))
        }
      case SetInvoiceDiscount(invoiceId, expectedVersion, discount) =>
        process(setInvoiceDiscount(invoiceId, expectedVersion, discount), msg.sender) { invoice =>
          listeners ! msg.copy(event = InvoiceDiscountSet(invoiceId, discount))
        }
      case SendInvoiceTo(invoiceId, expectedVersion, to) =>
        process(sendInvoiceTo(invoiceId, expectedVersion, to), msg.sender) { invoice =>
          listeners ! msg.copy(event = InvoiceSent(invoiceId, invoice, to))
        }
      case InvoicePaymentReceived(invoiceId, amount) =>
        process(payInvoice(invoiceId, None, amount), None) { invoice =>
          listeners ! msg.copy(event = InvoicePaid(invoiceId))
        }

    }
  }

  def process(validation: DomainValidation[Invoice], sender: Option[ActorRef])(onSuccess: Invoice => Unit) = {
    validation.foreach { invoice =>
      updateInvoices(invoice)
      onSuccess(invoice)
    }
    sender.foreach { sender =>
      sender ! validation
    }
  }

  def createInvoice(invoiceId: String): DomainValidation[DraftInvoice] = {
    readInvoices.get(invoiceId) match {
      case Some(invoice) => DomainError("invoice %s: already exists" format invoiceId).fail
      case None          => Invoice.create(invoiceId)
    }
  }

  def addInvoiceItem(invoiceId: String, expectedVersion: Option[Long], invoiceItem: InvoiceItem): DomainValidation[DraftInvoice] =
    updateDraftInvoice(invoiceId, expectedVersion) { invoice => invoice.addItem(invoiceItem) }

  def setInvoiceDiscount(invoiceId: String, expectedVersion: Option[Long], discount: BigDecimal): DomainValidation[DraftInvoice] =
    updateDraftInvoice(invoiceId, expectedVersion) { invoice => invoice.setDiscount(discount) }

  def sendInvoiceTo(invoiceId: String, expectedVersion: Option[Long], to: InvoiceAddress): DomainValidation[SentInvoice] =
    updateDraftInvoice(invoiceId, expectedVersion) { invoice => invoice.sendTo(to) }

  def payInvoice(invoiceId: String, expectedVersion: Option[Long], amount: BigDecimal): DomainValidation[PaidInvoice] =
    updateInvoice(invoiceId, expectedVersion) { invoice =>
      invoice match {
        case invoice: SentInvoice => invoice.pay(amount)
        case invoice: Invoice      => notSentError(invoiceId).fail
      }
    }

  def updateInvoice[B <: Invoice](invoiceId: String, expectedVersion: Option[Long])(f: Invoice => DomainValidation[B]): DomainValidation[B] =
    readInvoices.get(invoiceId) match {
      case None          => DomainError("invoice %s: does not exist" format invoiceId).fail
      case Some(invoice) => for {
        current <- Invoice.requireVersion(invoice, expectedVersion)
        updated <- f(invoice)
      } yield updated
    }

  def updateDraftInvoice[B <: Invoice](invoiceId: String, expectedVersion: Option[Long])(f: DraftInvoice => DomainValidation[B]): DomainValidation[B] =
    updateInvoice(invoiceId, expectedVersion) { invoice =>
      invoice match {
        case invoice: DraftInvoice => f(invoice)
        case invoice: Invoice      => notDraftError(invoiceId).fail
      }
    }

  private def updateInvoices(invoice: Invoice) =
    invoicesRef.single.transform(invoices => invoices + (invoice.id -> invoice))

  private def readInvoices =
    invoicesRef.single.get
}

object InvoiceProcessor {
  private[service] def notDraftError(invoiceId: String) =
    DomainError("invoice %s: not a draft invoice" format invoiceId)

  private[service] def notSentError(invoiceId: String) =
    DomainError("invoice %s: not a sent invoice" format invoiceId)
}
