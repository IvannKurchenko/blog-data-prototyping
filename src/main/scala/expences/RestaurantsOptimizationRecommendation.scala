package expences

import org.apache.spark.sql._
import org.apache.spark.sql.functions._

import java.time.{Clock, DayOfWeek, ZonedDateTime}

case class ExpenseRecommendation(userId: Long, recommendation: String)
case class MonthExpenses(userId: Long, currentMonth: Boolean, spend: Int)

class RestaurantsOptimizationRecommendation(clock: Clock) {

  /**
   * Generate recommendation per user to optimize expenses on restaurants during work week and consider options
   * such as groceries and cooking at home.
   */
  def recommendation(expenses: Dataset[Expense])(implicit session: SparkSession): Dataset[ExpenseRecommendation] = {
    import session.implicits._

    val today = ZonedDateTime.now(clock)
    val currentMonthStart = today.withDayOfMonth(1)
    val previousMonth = today.minusMonths(1)
    val previousMonthStart = previousMonth.withDayOfMonth(1)

    val weekDayRestaurantExpenses =
      expenses
        .filter(_.status == ExpenseStatuses.Completed)
        .filter(_.category.tags.contains(CategoryTags.Restaurants))
        .filter { expense =>
          ZonedDateTime.parse(expense.dateTime).isAfter(previousMonthStart)
        }
        .filter { expense =>
          val dateTime = ZonedDateTime.parse(expense.dateTime)
          val dayOfWeek = dateTime.getDayOfWeek
          dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY
        }
        .map { expense =>
          val dateTime = ZonedDateTime.parse(expense.dateTime)
          val userId = expense.userId
          val currentMonth = dateTime.isAfter(currentMonthStart)
          val spend = expense.spend.banknotes * 100 + expense.spend.coins // let's take simple model when coin is 1% of banknote nominal
          val exchangeSpend = expense.exchangeRate.fold(spend)(rate => Math.round(spend * rate))
          MonthExpenses(userId, currentMonth, exchangeSpend)
        }

    val currentMonthTotal =
      weekDayRestaurantExpenses
        .filter(_.currentMonth)
        .groupBy("userId")
        .sum("spend")
        .withColumnRenamed("sum(spend)", "spend")

    val previousMonthTotal =
      weekDayRestaurantExpenses
        .filter(!_.currentMonth)
        .groupBy("userId")
        .sum("spend")
        .withColumnRenamed("sum(spend)", "spend")

    currentMonthTotal
      .join(previousMonthTotal, "userId")
      .withColumn("delta", ((currentMonthTotal("spend") / previousMonthTotal("spend")) * lit(100)) - lit(100))
      .filter(col("delta") > 75)
      .select(currentMonthTotal("userId"), col("delta"))
      .withColumn("recommendation", concat(lit("Your expenses on restaurants on workdays increased on "), col("delta"), lit("%")))
      .as[ExpenseRecommendation]
  }
}
