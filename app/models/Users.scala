package models

import models.Users.UserView
import play.api.libs.json._
import slick.jdbc.GetResult
import utils.DateTime.Units
import utils.SlickAPI._
import utils.{Coordinates, DateTime, PointOfView, UsingImplicits}

/**
  * An Eventail user.
  *
  * @param id        the user's id
  * @param firstname the user's first name
  * @param lastname  the user's last name
  * @param username  the user's display name
  * @param mail      the user's e-mail address
  * @param rank      the user's rank index
  * @param lat       the user's current latitude
  * @param lon       the user's current longitude
  */
case class User(id: Int, firstname: String, lastname: String, username: String,
                mail: String, rank: Int, lat: Double, lon: Double, updated: DateTime)
		extends UsingImplicits[Users] {
	/** Whether the user is an administrator user. */
	def admin: Boolean = rank == Users.Rank.Admin

	/** Whether the user is restricted */
	def restricted: Boolean = rank >= Users.Rank.Restricted

	/** Whether the user is banned */
	def banned: Boolean = rank >= Users.Rank.Banned

	/** The current user's location, if available. */
	def location: Option[Coordinates] = {
		if (updated + 1.hour < DateTime.now) None
		else Some((lat, lon))
	}

	/**
	  * Constructs a new view of this user from an available implicit point of view.
	  *
	  * @param pov the point of view
	  * @return a new view of this user, from the given point of view
	  */
	def view(implicit pov: PointOfView) = new UserView(this)
}

//noinspection TypeAnnotation
class Users(tag: Tag) extends Table[User](tag, "users") {
	def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
	def firstname = column[String]("firstname")
	def lastname = column[String]("lastname")
	def username = column[String]("username")
	def mail = column[String]("mail")
	def rank = column[Int]("rank")
	def lat = column[Double]("lat")
	def lon = column[Double]("lon")
	def updated = column[DateTime]("updated")

	def pass = column[String]("pass")
	def cad = column[Option[String]]("cad")

	def * = (id, firstname, lastname, username, mail, rank, lat, lon, updated) <> (User.tupled, User.unapply)
}

object Users extends TableQuery(new Users(_)) {
	/** Raw SQL User reader */
	implicit val UserGetResult = GetResult(r => User(r.<<, r.<<, r.<<, r.<<, r.<<, r.skip.<<, r.<<, r.<<, r.skip.<<))

	/** Rank values */
	object Rank {
		final val Admin = 0
		final val User = 1
		final val Restricted = 2
		final val Banned = 3
	}

	/**
	  * A view of a User instance from a specific PointOfView.
	  *
	  * @param user the user being viewed
	  * @param pov  the point of view from which the user is being viewed
	  */
	class UserView(val user: User)(implicit pov: PointOfView) {
		/**
		  * Filtrates a field, exposing its value only if the condition is true.
		  *
		  * @param condition the condition
		  * @param value     the value to filter
		  * @tparam T the type of the value
		  * @return the filtered value
		  */
		private def filter[T](condition: Boolean, value: => T): Option[T] = {
			if (condition) Some(value)
			else None
		}

		private val isAdmin: Boolean = pov.admin
		private val isSelf: Boolean = pov.self(user)
		private val isFriend: Boolean = pov.friend(user)

		// Proxies and filters to the source user object
		def id: Int = user.id
		def username: String = user.username
		def firstname: Option[String] = filter(isFriend, user.firstname)
		def lastname: Option[String] = filter(isFriend, user.lastname)
		def mail: Option[String] = filter(isAdmin || isSelf, user.mail)
		def rank: Option[Int] = filter(isAdmin || isSelf, user.rank)
		def location: Option[Coordinates] = filter(isFriend, user.location).flatten
	}

	/** Implicit Writes instance for an UserView */
	implicit def UserViewWrites: Writes[UserView] = new Writes[UserView] {
		def writes(u: UserView): JsValue = Json.obj(
			"id" -> u.id,
			"username" -> u.username,
			"firstname" -> u.firstname,
			"lastname" -> u.lastname,
			"mail" -> u.mail,
			"rank" -> u.rank,
			"location" -> u.location
		)
	}

	/** Implicitly provide a Writes[User] if a PointOfView is available. */
	implicit def UserWrites(implicit pov: PointOfView): Writes[User] = new Writes[User] {
		def writes(user: User): JsValue = UserViewWrites.writes(user.view)
	}

	def findById(id: Rep[Int]): Query[Users, User, Seq] = Users.filter(_.id === id)
	def findById(ids: Seq[Int]): Query[Users, User, Seq] = Users.filter(_.id inSet ids)
}
