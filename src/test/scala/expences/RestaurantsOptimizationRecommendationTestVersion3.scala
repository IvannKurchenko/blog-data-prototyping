package expences

import expences.ExpenseStatuses.ExpenseStatus
import org.apache.spark.sql.SparkSession
import expences.prototype._
import monocle.macros.GenLens

import java.time._

/**
 * Third version of unit test for our complicated logic. In this version Prototype GoF pattern applied.
 */
class RestaurantsOptimizationRecommendationTestVersion3 extends munit.FunSuite {

  //domain specific implicits should be outside default `Prototype` implicits
  implicit val expenseStatusPrototype = new Prototype[ExpenseStatus](ExpenseStatuses.Completed)

  val zone: ZoneId = ZoneId.of("America/Chicago")

  val clock = {
    val instant = LocalDate.of(2021, 12, 15).atStartOfDay().toInstant(ZoneOffset.UTC)
    Clock.fixed(instant, zone)
  }

  val novemberMonday = ZonedDateTime.of(2021, 11, 8, 13, 0, 0, 0, zone).toString
  val novemberSunday = ZonedDateTime.of(2021, 11, 13, 10, 0, 0, 0, zone).toString
  val decemberMonday = ZonedDateTime.of(2021, 12, 7, 10, 0, 0, 0, zone).toString

  val restaurantCategory: Category = Category(1, "Restaurants", Set(CategoryTags.Restaurants, CategoryTags.Entertainment))
  val groceriesCategory: Category = Category(2, "Groceries", Set(CategoryTags.Grocery))

  object amounts {
    val usd = GenLens[Amount](_.currency).replace("usd")
    val ten = GenLens[Amount](_.banknotes).replace(10)
    val twenty = GenLens[Amount](_.banknotes).replace(20)
    val fifty = GenLens[Amount](_.banknotes).replace(50)
    val twoHundred = GenLens[Amount](_.banknotes).replace(200)

    val tenUsd = (usd compose ten).prototype
    val twentyUsd = (usd compose twenty).prototype
    val fiftyUsd = (usd compose fifty).prototype
    val twoHundredUsd = (usd compose twoHundred).prototype
  }

  object expenses {
    val user1 = GenLens[Expense](_.userId).replace(1)
    val restaurant = GenLens[Expense](_.category).replace(restaurantCategory)
    val grocery = GenLens[Expense](_.category).replace(groceriesCategory)

    val tenUsd = GenLens[Expense](_.spend).replace(amounts.tenUsd)
    val twentyUsd = GenLens[Expense](_.spend).replace(amounts.twentyUsd)
    val fiftyUsd = GenLens[Expense](_.spend).replace(amounts.fiftyUsd)
    val twoHundredUsd = GenLens[Expense](_.spend).replace(amounts.twoHundredUsd)

    val novemberWorkday = GenLens[Expense](_.dateTime).replace(novemberMonday)
    val novemberWeekend = GenLens[Expense](_.dateTime).replace(novemberSunday)
    val decemberWorkday = GenLens[Expense](_.dateTime).replace(decemberMonday)
  }

  val expensesData = {
    import expenses._
    Seq(
      (user1 compose novemberWorkday compose restaurant compose tenUsd).prototype,
      (user1 compose novemberWorkday compose grocery compose tenUsd).prototype,
      (user1 compose novemberWeekend compose restaurant compose fiftyUsd).prototype,

      (user1 compose decemberWorkday compose restaurant compose twentyUsd).prototype,
      (user1 compose decemberWorkday compose grocery compose twoHundredUsd).prototype,
    )
  }

  test("RestaurantsOptimizationRecommendation should produce recommendation for user #1") {
    val localSession = SparkSession.builder().appName("test").master("local[1]").getOrCreate()
    import localSession.implicits._

    val recommendation = new RestaurantsOptimizationRecommendation(clock)
    val recommendations = recommendation.recommendation(expensesData.toDS)(localSession)

    assertEquals(recommendations.count(), 1L)

    val actualRecommendation = recommendations.head(1).head
    val expectedRecommendation = ExpenseRecommendation(1, "Your expenses on restaurants on workdays increased on 100.0%")

    assertEquals(actualRecommendation, expectedRecommendation)
  }
}
