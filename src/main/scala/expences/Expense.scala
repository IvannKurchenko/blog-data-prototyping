package expences

import expences.CategoryTags.CategoryTag
import expences.ExpenseStatuses.ExpenseStatus

import java.net.URL
import java.time.{LocalDate, ZonedDateTime}
import java.util.Currency

case class Expense(id: Long,
                   dateTime: String,
                   userId: Long,
                   card: Card,
                   place: Address,
                   status: ExpenseStatus,
                   category: Category,
                   spend: Amount,
                   exchangeRate: Option[Float],
                   debit: Amount,
                   website: Option[String])

object ExpenseStatuses extends Enumeration {
  type ExpenseStatus = Value

  val Completed = Value("completed")
  val Rejected = Value("rejected")
  val Canceled = Value("canceled")
}

case class Card(id: Long,
                number: String,
                expires: LocalDate,
                holder: String,
                bank: String,
                currency: String,
                credit: Boolean)

case class Address(country: String,
                   city: String,
                   street: String,
                   building: Int)

case class Category(id: Long,
                    name: String,
                    tags: Set[CategoryTag])

object CategoryTags extends Enumeration {
  type CategoryTag = Value

  val Restaurants = Value("restaurants")
  val Entertainment = Value("entertainment")
  val Movies = Value("movies")
  val Transport = Value("transport")
  val Grocery = Value("grocery")
  val Clothing = Value("clothing")
}

case class Amount(banknotes: Int,
                  coins: Int,
                  currency: String)