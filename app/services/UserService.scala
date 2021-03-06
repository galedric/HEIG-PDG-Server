package services

import com.google.inject.{Inject, Singleton}
import models.{User, Users, Visit, Visits}
import org.apache.commons.codec.digest.DigestUtils
import play.api.libs.json.{JsObject, Json}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success
import utils.DateTime.Units
import utils.Implicits.FutureOps
import utils.SlickAPI._
import utils._

/**
  * Service responsible for every user-related operations.
  *
  * Services are only responsible for actual business logic.
  * Access control and permissions are managed by the Controller calling the service.
  *
  * @param ec an instance of ExecutionContext
  */
@Singleton
class UserService @Inject() (events: EventService, notify: NotificationsService)
                            (implicit ec: ExecutionContext, fs: FriendshipService) {
	/**
	  * Fetches a user.
	  *
	  * @param id the user ID
	  * @return
	  */
	def get(id: Int): Future[User] = {
		Users.findById(id).head
	}

	/**
	  * Defines the currently active device for the user.
	  *
	  * In practice, this should correspond to the session token used by the last
	  * launched instance of the Eventail client.
	  *
	  * The currently active device is checked before updating user current location
	  * and is the mechanism used to prevent concurrent updates of the user location
	  * from multiple devices at the same time.
	  *
	  * @param id  the user id
	  * @param cad the currently active device id
	  * @return a future that will be resolved when the database is updated
	  */
	def setCurrentActiveDevice(id: Int, cad: String): Future[_] = {
		val cadHash = Some(DigestUtils.sha1Hex(cad))
		Users.findById(id).map(u => u.cad).update(cadHash).run
	}

	/**
	  * Updates the user current location.
	  *
	  * The given current active device should match the one given to the last call
	  * to `setCurrentActiveDevice`.
	  *
	  * If the given device does not match the one stored in the database, the operation
	  * fails and the returned future will resolve to false.
	  *
	  * @param user   the user to update
	  * @param coords the current coordinates of the user
	  * @param cad    the device id issuing the update request,
	  *               must match the one in the database
	  * @return a future that will be resolved successfully if the operation was successful
	  */
	def updateLocation(user: User, coords: Coordinates, cad: String): Future[Unit] = {
		val cadHash = DigestUtils.sha1Hex(cad)
		val userQuery = Users.findById(user.id).filter(u => u.cad === cadHash || u.cad.isEmpty)
		val data = (coords.lat, coords.lon, Some(cadHash), DateTime.now)
		userQuery.map(user => (user.lat, user.lon, user.cad, user.updated)).update(data).run.map {
			case 0 => throw new IllegalStateException()
			case 1 => ()
		} andThenAsync {
			case Success(_) =>
				implicit val pov = PointOfView.forUser(user)
				events.unvisited(coords).flatMap { list =>
					Future.sequence(list.map { case (event, distance) =>
						notify.send(user.id, "NEARBY_EVENT", Json.obj(
							"event" -> event,
							"distance" -> distance
						)).flatMap { _ =>
							(Visits += Visit(event.id, user.id)).run
						}
					})
				}
		}
	}

	/**
	  * Updates the rank of the given user.
	  *
	  * @param id   the user to update
	  * @param rank the new rank for the user
	  * @return a future that will be resolved to true if the operation was successful,
	  *         false otherwise
	  */
	def promote(id: Int, rank: Int): Future[Boolean] = {
		Users.findById(id).map(_.rank).update(rank).run.map {
			case 1 => true
			case 0 => false
		}
	}

	/**
	  * Patches a user.
	  *
	  * Only the username, first name, last name, email address and password can
	  * be updated. The password must be given as plain text since this method
	  * will hash the value of the password field before updating the database.
	  *
	  * @param id    the user to update
	  * @param patch the patch document
	  * @return a future that will be resolved to the full, updated, user object
	  */
	def patch(id: Int, patch: JsObject): Future[User] = {
		Patch(Users.findById(id))
				.MapField("username", _.username)
				.MapField("firstname", _.firstname)
				.MapField("lastname", _.lastname)
				.MapField("mail", _.mail)
				.Map(d => (d \ "password").asOpt[String].map(p => BCrypt.hashpw(p, BCrypt.gensalt())), _.pass)
				.Execute(patch)
	}

	/**
	  * Searches for users matching the given query.
	  *
	  * This method only searches for non-friend users, also excluding the user itself.
	  * At most 50 results will be returned, sorted by increasing distance from the current location.
	  *
	  * @param q   the query string
	  * @param pov the point of view
	  * @return a list of users matching the query
	  */
	def search(q: String)(implicit pov: PointOfView): Future[Seq[User]] = {
		val (lat, lon) = pov.user.location.getOrElse(Coordinates(46.5197, 6.6323)).unpack
		val pattern = q.replaceAll("_|%", "").toLowerCase + "%"
		sql"""
			SELECT *, earth_distance(ll_to_earth(${lat}, ${lon}), ll_to_earth(lat, lon)) AS dist
			FROM users
			WHERE id != ${pov.user.id} AND (LOWER(username) LIKE ${pattern} OR LOWER(mail) LIKE ${pattern})
				AND NOT EXISTS (SELECT * FROM friends WHERE (a = ${pov.user.id} AND b = id) OR (a = id AND b = ${pov.user.id}))
			ORDER BY dist ASC
			LIMIT 50""".as[User].run
	}

	/**
	  * Searches nearby users.
	  *
	  * By default, only friends are returned. If the `all` parameter is
	  * set to true, even non-friend users are searched. Note that this
	  * require an administrator PointOfView, otherwise this parameter is
	  * ignored.
	  *
	  * @param point  the point around which users are searched
	  * @param radius the search radius (in meters)
	  * @param all    whether non-friend users should also be searched
	  * @param pov    the point of view
	  * @return a list of nearby users and their distance (in meters) from the given point
	  */
	def nearby(point: Coordinates, radius: Double, all: Boolean = false)
	          (implicit pov: PointOfView): Future[Seq[(User, Double)]] = {
		val (lat, lon) = point.unpack
		val expiration = (DateTime.now - 55.minutes).toTimestamp
		sql"""
			SELECT *, earth_distance(ll_to_earth(${lat}, ${lon}), ll_to_earth(lat, lon)) AS dist
			FROM users
			WHERE earth_box(ll_to_earth(${lat}, ${lon}), ${radius}) @> ll_to_earth(lat, lon)
				AND earth_distance(ll_to_earth(${lat}, ${lon}), ll_to_earth(lat, lon)) <= ${radius}
				AND updated > ${expiration}
				AND (${all && pov.admin} OR
					EXISTS(
						SELECT * FROM friends WHERE (a = ${pov.user.id} AND b = id) OR (a = id AND b = ${pov.user.id})
					))
			ORDER BY dist ASC
			LIMIT 100""".as[(User, Double)].run
	}
}
