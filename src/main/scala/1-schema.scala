package lc2018

import org.scalacheck.{Arbitrary, Gen}
import scala.collection.immutable.ListMap
import scalaz._, Scalaz._
import matryoshka._, implicits._

/**
  * Without further ado, let's define our main pattern-functor for the remaining of the session.
  */
sealed trait SchemaF[A]

// we'll use a ListMap to keep the ordering of the fields
final case class StructF[A](fields: ListMap[String, A]) extends SchemaF[A]
final case class ArrayF[A](element: A)                  extends SchemaF[A]
final case class BooleanF[A]()                          extends SchemaF[A]
final case class DateF[A]()                             extends SchemaF[A]
final case class DoubleF[A]()                           extends SchemaF[A]
final case class FloatF[A]()                            extends SchemaF[A]
final case class IntegerF[A]()                          extends SchemaF[A]
final case class LongF[A]()                             extends SchemaF[A]
final case class StringF[A]()                           extends SchemaF[A]

object SchemaF extends SchemaFToDataTypeAlgebras with SchemaFArbitrary {

  /**
    * As usual, we need to define a Functor instance for our pattern.
    */
  implicit val schemaFScalazFunctor: Functor[SchemaF] = new Functor[SchemaF] {
    def map[A, B](fa: SchemaF[A])(f: A => B): SchemaF[B] = fa match {
      case StructF(fields) => StructF(fields.map { case (name, value) => name -> f(value) })
      case ArrayF(element) => ArrayF(f(element))
      case BooleanF()      => BooleanF[B]()
      case DateF()         => DateF[B]()
      case DoubleF()       => DoubleF[B]()
      case FloatF()        => FloatF[B]()
      case IntegerF()      => IntegerF[B]()
      case LongF()         => LongF[B]()
      case StringF()       => StringF[B]()
    }
  }

  /**
    * It might be usefull to have a nice string representation of our schemas.
    *
    * Let say that we want a representation where:
    *   - simple types like `BooleanF()` or `DateF()` would be represented as `boolean` and `date` respectively.
    *   - arrays like `ArrayF(IntegerF())` would be represented as `[ integer ]`.
    *   - structs like `StructF(ListMap("foo" -> FloatF(), "bar" -> LongF())` would be represented as
    *     `{ foo: float, bar: long }`
    *
    * Because of the recursive nature of SchemaF, we cannot eagerly write a Show instance for SchemaF.
    * Fortunately matryoshka defines the Delay typeclass that is useful in such cases. It allows to "break
    * the infinite loop" by delaying the instantiation of Show[SchemaF[A]].
    *
    * matryoshka.implicits contains implicit functions that, given that Delay[Show, SchemaF] instance,
    * will provide a Show[T[SchemaF]] for any fix-point T.
    *
    */
  implicit val schemaFDelayShow: Delay[Show, SchemaF] = new Delay[Show, SchemaF] {
    def apply[A](showA: Show[A]): Show[SchemaF[A]] = new Show[SchemaF[A]] {
      override def show(schema: SchemaF[A]): Cord = schema match {
        case StructF(fields) =>
          val showFields = fields.map { case (k, v) => Cord(k) ++ Cord(":") ++ showA.show(v) }.toSeq
          Cord("{ ") ++ Cord.mkCord(Cord(", "), showFields: _*) ++ Cord("}")
        case ArrayF(element) => Cord("[") ++ showA.show(element) ++ Cord("]")
        case BooleanF()      => Cord("boolean")
        case DateF()         => Cord("date")
        case DoubleF()       => Cord("double")
        case FloatF()        => Cord("float")
        case IntegerF()      => Cord("integer")
        case LongF()         => Cord("long")
        case StringF()       => Cord("string")
      }
    }
  }

}

/**
  * Now that we have a proper pattern-functor, we need (co)algebras to go from our "standard" schemas to
  * our new and shiny SchemaF (and vice versa).
  *
  * Lets focus on Parquet schemas first. Parquet is a columnar data format that allows efficient processing
  * of large datasets in a distributed environment (eg Spark). In the Spark API, Parquet schemas are represented
  * as instances of the DataType type. So what we want to write here is a pair of (co)algebras that go from/to
  * SchemaF/DataType.
  *
  * NOTE: in order not to depend directly on Spark (and, hence, transitively on half of maven-central), we've copied
  * the definition of the DataType trait and its subclasses in the current project under
  * `spark/src/main/scala/DataType.scala`.
  */
trait SchemaFToDataTypeAlgebras {

  import org.apache.spark.sql.types._

  /**
    * As usual, simply a function from SchemaF[DataType] to DataType
    */
  def schemaFToDataType: Algebra[SchemaF, DataType] = {
    case StringF()       => StringType
    case LongF()         => LongType
    case IntegerF()      => IntegerType
    case FloatF()        => FloatType
    case DoubleF()       => DoubleType
    case DateF()         => DateType
    case BooleanF()      => BooleanType
    case ArrayF(element) => ArrayType(element, false)
    case StructF(fields) => StructType(fields.map { case (k, v) => StructField(k, v) }.toArray)
  }

  /**
    * And the other way around, a function from DataType to SchemaF[DataType]
    */
  def dataTypeToSchemaF: Coalgebra[SchemaF, DataType] = {
    case StringType            => StringF()
    case LongType              => LongF()
    case IntegerType           => IntegerF()
    case FloatType             => FloatF()
    case DoubleType            => DoubleF()
    case DateType              => DateF()
    case BooleanType           => BooleanF()
    case ArrayType(element, _) => ArrayF(element)
    case StructType(fields)    => StructF(ListMap(fields.map(sf => sf.name -> sf.dataType): _*))
  }

  /**
    * This pair of (co)algebras allows us to create a Birecursive[DataType, SchemaF] instance "for free".
    *
    * Such instance witnesses the fact that we can use a DataType in schemes that would normally apply to SchemaF.
    * For example, suppose that we have:
    *
    * {{{
    *   val parquet: DataType = ???
    *   val toAvro: Algebra[SchemaF, avro.Schema] = ???
    * }}}
    *
    * If we have the instance bellow in scope (and the necessary implicits from matryoshka.implicits), we can now write
    *
    * {{{
    *   parquet.cata(toAvro)
    * }}}
    *
    * Instead of
    *
    * {{{
    *   parquet.hylo(dataTypeToSchemaf, toAvro)
    * }}}
    *
    * And the same goes with `ana` and any Coalgebra[SchemaF, X].
    */
  implicit def dataTypeSchemaBirecursive: Birecursive.Aux[DataType, SchemaF] =
    Birecursive.fromAlgebraIso(schemaFToDataType, dataTypeToSchemaF)
}

/**
  * Everything looks nice, but don't you feel we are missing something?
  *
  * I mean, think about it for a minute and meet me 20 lines bellow.
  *
  *
  *
  *
  *
  *
  *
  *
  *
  *
  *
  *
  *
  *
  *
  *
  *
  *
  *
  * Did you guess?
  *
  *
  *
  * You're right of course! We still have to write tests!
  *
  * Let's meet again in `src/test/scala/1-schema/ParquetSpec.scala`.
  */
trait SchemaFArbitrary {

  implicit def schemaFDelayArbitrary: Delay[Arbitrary, SchemaF] = new Delay[Arbitrary, SchemaF] {
    import Arbitrary._
    import Gen._

    def apply[A](A: Arbitrary[A]): Arbitrary[SchemaF[A]] = Arbitrary(oneOf(arbitrary[Boolean]))
  }
}
