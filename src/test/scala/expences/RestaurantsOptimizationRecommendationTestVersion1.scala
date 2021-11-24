package expences

import org.apache.spark.sql.SparkSession

import java.time._

/**
 * First version of unit test for our complicated logic, which includes straight forward test data generation.
 */
class RestaurantsOptimizationRecommendationTestVersion1 extends munit.FunSuite {

  val usd = "USD"
  val zone: ZoneId = ZoneId.of("America/Chicago")

  val clock = {
    val instant = LocalDate.of(2021, 12, 15).atStartOfDay().toInstant(ZoneOffset.UTC)
    Clock.fixed(instant, zone)
  }

  val card = Card(
    id = 1,
    number = "1234 5678 9012 3456",
    expires = LocalDate.of(2022, 12, 1),
    holder = "John Doe",
    bank = "Bank of Earth",
    currency = usd,
    credit = true
  )

  val restaurantAddress = Address(
    country = "USA",
    city = "Chicago",
    street = "Michigan",
    building = 1
  )

  val groceryAddress = Address(
    country = "USA",
    city = "Chicago",
    street = "Michigan",
    building = 2
  )

  val restaurantCategory: Category = Category(1, "Restaurants", Set(CategoryTags.Restaurants, CategoryTags.Entertainment))
  val groceriesCategory: Category = Category(2, "Groceries", Set(CategoryTags.Grocery))

  val expenses = Seq(
    // User #1 spend spend 10$ in restaurant at Monday 8 of November 2021.
    Expense(
      id = 0,
      dateTime = ZonedDateTime.of(2021, 11, 8, 13, 0, 0, 0, zone).toString,
      userId = 1,
      card = card,
      place = restaurantAddress,
      status = ExpenseStatuses.Completed,
      category = restaurantCategory,
      spend = Amount(10, 0, usd),
      exchangeRate = None,
      debit = Amount(10_000, 0, usd),
      website = None
    ),

    // User #1 spend spend 50$ in grocery at Sunday 13 of November 2021.
    Expense(
      id = 0,
      dateTime = ZonedDateTime.of(2021, 11, 13, 10, 0, 0, 0, zone).toString,
      userId = 1,
      card = card,
      place = restaurantAddress,
      status = ExpenseStatuses.Completed,
      category = groceriesCategory,
      spend = Amount(50, 0, usd),
      exchangeRate = None,
      debit = Amount(11_000, 0, usd),
      website = None
    ),

    // User #1 spend spend 20$ in restaurant at Monday 6 of December 2021.
    Expense(
      id = 0,
      dateTime = ZonedDateTime.of(2021, 12, 6, 13, 0, 0, 0, zone).toString,
      userId = 1,
      card = card,
      place = restaurantAddress,
      status = ExpenseStatuses.Completed,
      category = restaurantCategory,
      spend = Amount(20, 0, usd),
      exchangeRate = None,
      debit = Amount(12_000, 0, usd),
      website = None
    ),

    // User #1 spend spend 200$ in grocery at Tuesday 7 of December 2021.
    Expense(
      id = 0,
      dateTime = ZonedDateTime.of(2021, 12, 7, 10, 0, 0, 0, zone).toString,
      userId = 1,
      card = card,
      place = restaurantAddress,
      status = ExpenseStatuses.Completed,
      category = groceriesCategory,
      spend = Amount(200, 0, usd),
      exchangeRate = None,
      debit = Amount(8_000, 0, usd),
      website = None
    )
  )

  test("RestaurantsOptimizationRecommendation should produce recommendation for user #1") {
    val localSession = SparkSession.builder().appName("test").master("local[1]").getOrCreate()
    import localSession.implicits._

    val recommendation = new RestaurantsOptimizationRecommendation(clock)
    val recommendations = recommendation.recommendation(expenses.toDS)(localSession)

    assertEquals(recommendations.count(), 1L)

    val actualRecommendation = recommendations.head(1).head
    val expectedRecommendation = ExpenseRecommendation(1, "Your expenses on restaurants on workdays increased on 100.0%")

    assertEquals(actualRecommendation, expectedRecommendation)
  }
}
