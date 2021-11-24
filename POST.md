# Complex test data prototyping with Shapeless and Monocle

## Introduction
This article describes how `Shapeless` and `Monocle` libraries, along with GoF patterns and type classes derivations can help
generate complex data for unit tests in an easy way.
I'd like to ask for some patience in advance: there is going to be plenty of code because the problem I'm going to
present is pretty visible after certain code-base size and domain model complexity.

## System under the test 
For the sake of the article example, let's consider Spark application for personal expenses reports calculations.
The main goal of such an application is to provide actionable recommendations for the user on how he/she can save some money based on expenses history.
For instance, check if a user spends too much money on lunch in restaurants during the workweek.

Let's begin with domain model definition:
```scala

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
```

Despite the fact, that for now, our app will produce only a single financial recommendation, the domain model was designed keeping in mind system flexibility and covering other use cases, such as shopping online or abroad.
So let's consider the recommendation we are going to generate:

The application calculates the total amount of money spent on restaurants in the workweek during the current and previous month.
If those values differ by more than 75%, we can suggest optimizing expenses in this category.
For simplicity, let's say we generate this report once per month.

```scala
import org.apache.spark.sql._
import org.apache.spark.sql.functions._

import java.time.{Clock, DayOfWeek, ZonedDateTime}

case class ExpenseRecommendation(userId: Long, recommendation: String)
case class MonthExpenses(userId: Long, currentMonth: Boolean, spend: Int)

class RestaurantsOptimizationRecommendation(clock: Clock) {

  /**
   * Generate recommendation per user to optimize expenses on restaurants during work week and consider options
   * such as groceries and cooking at home. Restaurant expenses are those which contains `CategoryTags.Restaurants` in it.
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
```

## Writing test
Plenty of business logic has been implemented in the previous section. As a next step, let's try to write a unit test for our job.
In this unit test, we will check the simplest scenario - a single user spend twice more money on restaurants  in December 2021 compared to November 2021.
A crucial part of this test is data. To test other parts of logic, among restaurants expenses,
let's add some grocery expenses and weekend expenses.

For unit test implementation [Munit](https://scalameta.org/munit/) library was used.

```scala
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
````

And here we can spot the first problem: we have to write a lot of similar and straightforward code to instantiate data
for our tests, such as `Expense` objects. Implementation and maintenance of such a codebase are tedious and problematic
because each domain model change leads to many small fixes in the tests.

Along with this, we need to fill plenty of fields with values we really don't care about in scope of tests,
such as `card` field in `Expense` class.
First thought would be to use default values, but this is not recommended way to go, because test and production code 
should be decoupled as much as possible. E.g. we can make `card` or `address` defaulting to some dummy values,
but this would lead to unexpected mistakes in production code.

## Test refactoring first iteration
I was inspired by [GoF Prototype pattern](https://en.wikipedia.org/wiki/Prototype_pattern) to solve problem with data copy-pasting.
Essentially, we can instantiate prototype of complex object ([Null object pattern](https://en.wikipedia.org/wiki/Null_object_pattern)) and then make it copy with more specific values we care about.
Luckily, this is pretty easy to do with Scala's `copy` method for case classes.

After refactoring we get next result:
```scala
class RestaurantsOptimizationRecommendationTestVersion2 extends munit.FunSuite {

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

  val addressPrototype = Address(
    country = "USA",
    city = "Chicago",
    street = "Michigan",
    building = 1
  )

  val restaurantAddress = addressPrototype.copy(building = 1)
  val groceryAddress = addressPrototype.copy(building = 2)

  val categoryPrototype: Category = Category(1, "", Set.empty)
  val restaurantCategory: Category = Category(1, "Restaurants", Set(CategoryTags.Restaurants, CategoryTags.Entertainment))
  val groceriesCategory: Category = Category(2, "Groceries", Set(CategoryTags.Grocery))

  val amountPrototype = Amount(0, 0, usd)

  val expensePrototype = Expense(
    id = 0,
    dateTime = "",
    userId = 1,
    card = card,
    place = restaurantAddress,
    status = ExpenseStatuses.Completed,
    category = categoryPrototype,
    spend = amountPrototype,
    exchangeRate = None,
    debit = amountPrototype,
    website = None
  )

  val expenses = Seq(
    // User #1 spend spend 10$ in restaurant at Monday 8 of November 2021.
    expensePrototype.copy(
      dateTime = ZonedDateTime.of(2021, 11, 8, 13, 0, 0, 0, zone).toString,
      category = restaurantCategory,
      spend = amountPrototype.copy(banknotes = 10),
    ),

    // User #1 spend spend 50$ in grocery at Sunday 13 of November 2021.
    expensePrototype.copy(
      dateTime = ZonedDateTime.of(2021, 11, 13, 10, 0, 0, 0, zone).toString,
      category = groceriesCategory,
      spend = amountPrototype.copy(banknotes = 50),
    ),

    // User #1 spend spend 20$ in restaurant at Monday 6 of December 2021.
    expensePrototype.copy(
      dateTime = ZonedDateTime.of(2021, 12, 6, 13, 0, 0, 0, zone).toString,
      category = restaurantCategory,
      spend = amountPrototype.copy(banknotes = 20),
    ),

    // User #1 spend spend 200$ in grocery at Tuesday 7 of December 2021.
    expensePrototype.copy(
      dateTime = ZonedDateTime.of(2021, 12, 7, 10, 0, 0, 0, zone).toString,
      category = groceriesCategory,
      spend = amountPrototype.copy(banknotes = 200),
    )
  )

  test("RestaurantsOptimizationRecommendation should produce recommendation for user #1") {
    // Same test code
  }
}
```

Looks better now, but still is not good enough. We can spot two other problems in this code: 
- Prototype instantiation remains tedious, while this is writing simple code, which can be generated for us.\
 `Shapeless` type classes derivation capabilities can help to solve this task. 

- Using `copy` is not composable: if we want to apply the same transformation but for different objects, we would need to repeat the same `copy` invocation.\
  Answer to this problem - optics, `Monocle` in particular.

## Prototype instantiation
In order to instantiate any case class prototype, we need to instantiate it with some "default" values, such `""` for string,
`0` for integers, `None` for options, and so on. 
[shapeless](https://github.com/milessabin/shapeless) is a perfect tool to solve this. Generating such prototype instance
sounds like similar deriving type class, which shapeless perfectly does. In our case, `Prototype` type class should return
the prototype value.

Implementation looks next: 
```scala
import shapeless._

import java.time.{LocalDate, ZoneId, ZonedDateTime}
import scala.annotation.implicitNotFound

/**
 * Type class to generate test data prototype
 * @tparam T prototype
 */
@implicitNotFound("Cannot resolve prototype of the type ${T}")
class Prototype[T](val value: T)

trait PrototypeLowPriority {
  implicit val deriveHNil: Prototype[HNil] = new Prototype(HNil)

  implicit def deriveHCons[V, T <: HList](implicit sv: => Prototype[V], st: Prototype[T]): Prototype[V :: T] = {
    new Prototype(sv.value :: st.value)
  }

  implicit def deriveInstance[F, G](implicit gen: Generic.Aux[F, G], sg: => Prototype[G]): Prototype[F] = {
    new Prototype(gen.from(sg.value))
  }
}

object Prototype extends PrototypeLowPriority {
  def apply[T](implicit prototype: Prototype[T]): Prototype[T] = prototype

  implicit val stringPrototype: Prototype[String] = new Prototype("")
  implicit val intPrototype: Prototype[Int] = new Prototype(0)
  implicit val longPrototype: Prototype[Long] = new Prototype(0)
  implicit val floatPrototype: Prototype[Float] = new Prototype(0)
  implicit val doublePrototype: Prototype[Double] = new Prototype(0)
  implicit val booleanPrototype: Prototype[Boolean] = new Prototype(false)

  implicit val localDatePrototype = new Prototype[LocalDate](LocalDate.ofYearDay(1970, 1))
  implicit val zonedDateTimePrototype = new Prototype[ZonedDateTime](ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneId.systemDefault()))

  implicit def optionPrototype[T]: Prototype[Option[T]] = new Prototype(None)
  implicit def setPrototype[T]: Prototype[Set[T]] = new Prototype(Set.empty)
  implicit def listPrototype[T]: Prototype[List[T]] = new Prototype(List.empty)
}

trait PrototypeSyntax {
  def prototype[T](implicit p: Prototype[T]): T = p.value

  // this piece of syntax is for monocle and we will need it later
  implicit class LensPrototypeSyntax[T](lens: T => T) {
    def prototype(implicit p: Prototype[T]): T = lens(p.value)
  }
}
```

Let's create also package object for convenient import:
```scala
package object prototype extends PrototypeSyntax
```

Let's see small demo:
```scala
prototype[Amount] // creates Amount(0,0,) object instance
```

Perfect, first part is ready.

## Prototype modifications
After we have prototype generation in place, we want to apply various composable (!) transformations to it.
[monocle 's `Lens`](https://www.optics.dev/Monocle/docs/optics/lens) is another great tool that can help us with this.
We can split different fields transformation to an individual lens to compose at the end. For instance:

```scala
val novemberMonday = ZonedDateTime.of(2021, 11, 8, 13, 0, 0, 0, zone).toString

val user1 = GenLens[Expense](_.userId).replace(1)
val restaurant = GenLens[Expense](_.category).replace(restaurantCategory)
val novemberWorkday = GenLens[Expense](_.dateTime).replace(novemberMonday)

val expense = prototype[Expence] 
// User #1 spend 200$ in grocery in Monday 8 of November 2021
(user1 compose novemberWorkday compose grocery compose twoHundredUsd)(expense)

// This will give same result as previous line. `PrototypeSyntax.prototype` method added as shortcut for such construction.
(user1 compose novemberWorkday compose grocery compose twoHundredUsd).prototype
```

## Test final refactoring
Having all the tools and approaches we considered before, let's put all the parts together in final test result:
```scala
import expences.ExpenseStatuses.ExpenseStatus
import org.apache.spark.sql.SparkSession
import expences.prototype._
import monocle.macros.GenLens

import java.time._

class RestaurantsOptimizationRecommendationTestVersion3 extends munit.FunSuite {

  //Domain specific implicits should be outside default `Prototype` implicits. 
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
    // Same test code
  }
}
```

Looks much cleaner now, isn't it?

## Conclusion
In conclusion, I want to share the pros and cons of this approach.
Pros:
- Easy to create complex structures with only fields and values need for test purposes.
- No copy-pasting and tedious refactoring for model changes.
- Clean and readable code to show the intent of each data entry.

- Cons:
- Additional small self-written framework to support;

A complete example can be found on [Github](https://github.com/IvannKurchenko/blog-data-prototyping)

## Further reading and references
- [Baeldung Monocle tutorial](https://www.baeldung.com/scala/monocle-optics)
- [Monocle Lens Doc](https://www.optics.dev/Monocle/docs/optics/lens)
- [shapeless type class derivation example](https://github.com/milessabin/shapeless/blob/main/examples/src/main/scala/shapeless/examples/derivation.scala)
