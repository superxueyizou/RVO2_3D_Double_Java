package rvo2;

import java.util.Vector;

import sim.util.Double3D;
import sim.util.MutableDouble3D;

public class Agent {
	
	/**
	 * \brief   A sufficiently small positive number.
	 */
	final double RVO_EPSILON = 0.00001;
	
	RVOSimulator ptrSim_;
	
	int id_;	
	Double3D position_;	
	Double3D velocity_;
	double radius_;
	Double3D prefVelocity_;	
	int maxNeighbors_;
	double maxSpeed_;
	double neighborDist_;	
	double timeHorizon_;
	MutableDouble3D newVelocity_=new MutableDouble3D();
	
	Vector<Pair<Double, Agent> > agentNeighbors_ = new Vector<Pair<Double, Agent> >();
	Vector<Plane> orcaPlanes_= new Vector<Plane>();	

	/**
	 * \brief   finalructs an agent instance.
	 * \param   sim  The simulator instance.
	 */
	Agent(RVOSimulator ptrSim)
	{
		this.ptrSim_=ptrSim;
		this.id_=0;
		this.maxNeighbors_=0;
		this.maxSpeed_=0.0;
		this.neighborDist_=0.0;
		this.radius_=0.0;
		this.timeHorizon_=0.0;
	}
	
	/**
	 * \brief   Computes the neighbors of this agent.
	 */
	void computeNeighbors()
	{
		agentNeighbors_.clear();

		if (maxNeighbors_ > 0) {
			ptrSim_.ptrkdTree_.computeAgentNeighbors(this, neighborDist_ * neighborDist_);
		}
	}

	/**
	 * \brief   Computes the new velocity of this agent.
	 */
	void computeNewVelocity()
	{
		orcaPlanes_.clear();
		final double invTimeHorizon = 1.0f / timeHorizon_;

		/* Create agent ORCA planes. */
		for (int i = 0; i < agentNeighbors_.size(); ++i) 
		{
			final Agent other = agentNeighbors_.get(i).getSecond();
			final Double3D relativePosition = other.position_.subtract(position_);
			final Double3D relativeVelocity = velocity_.subtract(other.velocity_);
			final double distSq =  relativePosition.lengthSq();
			final double combinedRadius = radius_ + other.radius_;
			final double combinedRadiusSq = combinedRadius*combinedRadius;

			Plane plane = new Plane();
			Double3D u;

			if (distSq > combinedRadiusSq) 
			{
				/* No collision. */
				final Double3D w = relativeVelocity.subtract( relativePosition.multiply(invTimeHorizon) );
				/* Vector from cutoff center to relative velocity. */
				final double wLengthSq =  w.lengthSq();

				final double dotProduct =  w.dot(relativePosition);

				if (dotProduct < 0.0f && dotProduct*dotProduct > combinedRadiusSq * wLengthSq) {
					/* Project on cut-off circle. */
					final double wLength =  Math.sqrt(wLengthSq);
					final Double3D unitW = w.multiply(1.0f / wLength);

					plane.normal = unitW;
					u = unitW.multiply(combinedRadius * invTimeHorizon - wLength);
				}
				else 
				{
					/* Project on cone. */
					final double a = distSq;
					final double b =  relativePosition.dot(relativeVelocity);
					final double c =  (relativeVelocity.lengthSq() - (Utils.cross(relativePosition, relativeVelocity)).lengthSq() / (distSq - combinedRadiusSq));
					final double t =  ((b + Math.sqrt(b*b - a * c)) / a);
					final Double3D w2 = relativeVelocity.subtract( relativePosition.multiply(t) );
					final double wLength =  w2.length();
					final Double3D unitW = w2.multiply(1/ wLength);

					plane.normal = unitW;
					u = unitW.multiply(combinedRadius * t - wLength);
				}
			}
			else 
			{
				/* Collision. */
				final double invTimeStep = 1.0f / ptrSim_.timeStep_;
				final Double3D w = relativeVelocity.subtract( relativePosition.multiply(invTimeStep) );
				final double wLength =  w.length();
				final Double3D unitW = w.multiply(1/ wLength);

				plane.normal = unitW;
				u = unitW.multiply(combinedRadius * invTimeStep - wLength);
			}

			plane.point = velocity_.add(u.multiply(0.5));
			orcaPlanes_.add(plane);
		}

		final int planeFail = linearProgram3(orcaPlanes_, maxSpeed_, prefVelocity_, false, newVelocity_);

		if (planeFail < orcaPlanes_.size()) {
			linearProgram4(orcaPlanes_, planeFail, maxSpeed_, newVelocity_);
		}
	}

	/**
	 * \brief   Inserts an agent neighbor into the set of neighbors of this agent.
	 * \param   agent    A pointer to the agent to be inserted.
	 * \param   rangeSq  The squared range around this agent.
	 */
	void insertAgentNeighbor(final Agent agent, MutableDouble rangeSq)
	{
		if (this != agent) {
			final double distSq =  position_.subtract(agent.position_).lengthSq();

			if (distSq < rangeSq.doubleValue) {
				if (agentNeighbors_.size() < maxNeighbors_) {
					agentNeighbors_.add(new Pair<Double, Agent>(distSq, agent));
				}

				int i = agentNeighbors_.size() - 1;

				while (i != 0 && distSq < agentNeighbors_.get(i - 1).getFirst()) {
					
					agentNeighbors_.set(i, new Pair<Double, Agent>(agentNeighbors_.get(i-1).getFirst(),agentNeighbors_.get(i-1).getSecond()) );
					--i;
				}

				agentNeighbors_.set(i,new Pair<Double, Agent>(distSq, agent));

				if (agentNeighbors_.size() == maxNeighbors_) {
					rangeSq.doubleValue = agentNeighbors_.lastElement().getFirst();
				}
			}
		}
	}

	/**
	 * \brief   Updates the three-dimensional position and three-dimensional velocity of this agent.
	 */
	void update()
	{
		velocity_ = new Double3D(newVelocity_);
		position_ = position_.add( velocity_.multiply( ptrSim_.timeStep_) );
	}

	/**
	 * \brief   Solves a one-dimensional linear program on a specified line subject to linear finalraints defined by planes and a spherical finalraint.
	 * \param   planes        Planes defining the linear finalraints.
	 * \param   planeNo       The plane on which the line lies.
	 * \param   line          The line on which the 1-d linear program is solved
	 * \param   radius        The radius of the spherical finalraint.
	 * \param   optVelocity   The optimization velocity.
	 * \param   directionOpt  True if the direction should be optimized.
	 * \param   result        A reference to the result of the linear program.
	 * \return  True if successful.
	 */
	boolean linearProgram1(final Vector<Plane> planes, int planeNo, final Line line, double radius, final Double3D optVelocity, boolean directionOpt, MutableDouble3D result)
	{
		final double dotProduct =  line.point.dot(line.direction);
		final double discriminant =  ((dotProduct*dotProduct) + (radius*radius) - (line.point.lengthSq()));

		if (discriminant < 0.0f) {
			/* Max speed sphere fully invalidates line. */
			return false;
		}

		final double sqrtDiscriminant =  Math.sqrt(discriminant);
		double tLeft = -dotProduct - sqrtDiscriminant;
		double tRight = -dotProduct + sqrtDiscriminant;

		for (int i = 0; i < planeNo; ++i) {
			final double numerator =  planes.get(i).point.subtract(line.point).dot(planes.get(i).normal);
			final double denominator =  line.direction.dot(planes.get(i).normal);

			if ((denominator*denominator) <= RVO_EPSILON) {
				/* Lines line is (almost) parallel to plane i. */
				if (numerator > 0.0f) {
					return false;
				}
				else {
					continue;
				}
			}

			final double t = numerator / denominator;

			if (denominator >= 0.0f) {
				/* Plane i bounds line on the left. */
				tLeft = Math.max(tLeft, t);
			}
			else {
				/* Plane i bounds line on the right. */
				tRight = Math.min(tRight, t);
			}

			if (tLeft > tRight) {
				return false;
			}
		}

		if (directionOpt) {
			/* Optimize direction. */
			if (optVelocity.dot(line.direction) > 0.0f) {
				/* Take right extreme. */
				result.setTo(line.point.add(line.direction.multiply(tRight)));
			}
			else {
				/* Take left extreme. */
				result.setTo( line.point.add(line.direction.multiply(tLeft) ));
			}
		}
		else {
			/* Optimize closest point. */
			final double t =  line.direction.dot(optVelocity.subtract(line.point));

			if (t < tLeft) {
				result.setTo(line.point.add(line.direction.multiply(tLeft)));
			}
			else if (t > tRight) {
				result.setTo(line.point.add(line.direction.multiply(tRight)));
			}
			else {
				result.setTo(line.point.add(line.direction.multiply(t)));
			}
		}

		return true;
	}

	/**
	 * \brief   Solves a two-dimensional linear program on a specified plane subject to linear finalraints defined by planes and a spherical finalraint.
	 * \param   planes        Planes defining the linear finalraints.
	 * \param   planeNo       The plane on which the 2-d linear program is solved
	 * \param   radius        The radius of the spherical finalraint.
	 * \param   optVelocity   The optimization velocity.
	 * \param   directionOpt  True if the direction should be optimized.
	 * \param   result        A reference to the result of the linear program.
	 * \return  True if successful.
	 */
	boolean linearProgram2(final Vector<Plane> planes, int planeNo, double radius, final Double3D optVelocity, boolean directionOpt, MutableDouble3D result)
	{
		final double planeDist =  planes.get(planeNo).point.dot( planes.get(planeNo).normal);
		final double planeDistSq = (planeDist*planeDist);
		final double radiusSq = (radius*radius);

		if (planeDistSq > radiusSq) {
			/* Max speed sphere fully invalidates plane planeNo. */
			return false;
		}

		final double planeRadiusSq = radiusSq - planeDistSq;

		final Double3D planeCenter = planes.get(planeNo).normal.multiply(planeDist);

		if (directionOpt) {
			/* Project direction optVelocity on plane planeNo. */
			final Double3D planeOptVelocity = optVelocity.subtract( planes.get(planeNo).normal.multiply(optVelocity.dot(planes.get(planeNo).normal)) );
			final double planeOptVelocityLengthSq =  (planeOptVelocity.lengthSq());

			if (planeOptVelocityLengthSq <= RVO_EPSILON) {
				result.setTo(planeCenter);
			}
			else {
				result.setTo( planeCenter.add(planeOptVelocity.multiply(Math.sqrt(planeRadiusSq / planeOptVelocityLengthSq))) );
			}
		}
		else {
			/* Project point optVelocity on plane planeNo. */
			result.setTo( optVelocity.add( planes.get(planeNo).normal.multiply( planes.get(planeNo).point.subtract(optVelocity).dot(planes.get(planeNo).normal)) ) );

			/* If outside planeCircle, project on planeCircle. */
			if (result.lengthSq() > radiusSq) {
				final Double3D planeResult = new Double3D(result).subtract(planeCenter);
				final double planeResultLengthSq =  (planeResult.lengthSq());
				result.setTo( planeCenter.add(planeResult.multiply(Math.sqrt(planeRadiusSq / planeResultLengthSq))) );
			}
		}

		for (int i = 0; i < planeNo; ++i) {
			if (planes.get(i).normal.dot(planes.get(i).point.subtract(new Double3D(result))) > 0.0f) {
				/* Result does not satisfy finalraint i. Compute new optimal result. */
				/* Compute intersection line of plane i and plane planeNo. */
				Double3D crossProduct = Utils.cross(planes.get(i).normal, planes.get(planeNo).normal);

				if ( crossProduct.lengthSq() <= RVO_EPSILON) {
					/* Planes planeNo and i are (almost) parallel, and plane i fully invalidates plane planeNo. */
					return false;
				}

				Line line = new Line();
				line.direction = crossProduct.normalize();
				final Double3D lineNormal = Utils.cross(line.direction, planes.get(planeNo).normal);
				line.point = planes.get(planeNo).point.add( lineNormal.multiply( planes.get(i).point.subtract(planes.get(planeNo).point).dot(planes.get(i).normal) /lineNormal.dot(planes.get(i).normal) ) );

				if (!linearProgram1(planes, i, line, radius, optVelocity, directionOpt, result)) {
					return false;
				}
			}
		}

		return true;
	}

	/**
	 * \brief   Solves a three-dimensional linear program subject to linear finalraints defined by planes and a spherical finalraint.
	 * \param   planes        Planes defining the linear finalraints.
	 * \param   radius        The radius of the spherical finalraint.
	 * \param   optVelocity   The optimization velocity.
	 * \param   directionOpt  True if the direction should be optimized.
	 * \param   result        A reference to the result of the linear program.
	 * \return  The number of the plane it fails on, and the number of planes if successful.
	 */
	int linearProgram3(final Vector<Plane> planes, double radius, final Double3D optVelocity, boolean directionOpt, MutableDouble3D result)
	{
		if (directionOpt) {
			/* Optimize direction. Note that the optimization velocity is of unit length in this case. */
			result.setTo(optVelocity.multiply(radius));
		}
		else if (optVelocity.lengthSq() > radius*radius) {
			/* Optimize closest point and outside circle. */
			result.setTo(optVelocity.normalize().multiply(radius));
		}
		else {
			/* Optimize closest point and inside circle. */
			result.setTo(optVelocity);
		}

		for (int i = 0; i < planes.size(); ++i) {
			if (planes.get(i).normal.dot(planes.get(i).point.subtract(new Double3D(result))) > 0.0f) {
				/* Result does not satisfy finalraint i. Compute new optimal result. */
				final Double3D tempResult = new Double3D(result);

				if (!linearProgram2(planes, i, radius, optVelocity, directionOpt, result)) {
					result.setTo(tempResult);
					return i;
				}
			}
		}

		return planes.size();
	}

	/**
	 * \brief   Solves a four-dimensional linear program subject to linear finalraints defined by planes and a spherical finalraint.
	 * \param   planes     Planes defining the linear finalraints.
	 * \param   beginPlane The plane on which the 3-d linear program failed.
	 * \param   radius     The radius of the spherical finalraint.
	 * \param   result     A reference to the result of the linear program.
	 */
	void linearProgram4(final Vector<Plane> planes, int beginPlane, double radius, MutableDouble3D result)
	{
		double distance = 0.0f;

		for (int i = beginPlane; i < planes.size(); ++i) {
			if (planes.get(i).normal.dot(planes.get(i).point.subtract(new Double3D(result)) ) > distance) {
				/* Result does not satisfy finalraint of plane i. */
				Vector<Plane> projPlanes = new Vector<Plane>();

				for (int j = 0; j < i; ++j) {
					Plane plane= new Plane();

					final Double3D crossProduct = Utils.cross(planes.get(j).normal, planes.get(i).normal);

					if (crossProduct.lengthSq() <= RVO_EPSILON) {
						/* Plane i and plane j are (almost) parallel. */
						if (planes.get(i).normal.dot(planes.get(j).normal) > 0.0f) {
							/* Plane i and plane j point in the same direction. */
							continue;
						}
						else {
							/* Plane i and plane j point in opposite direction. */
							plane.point = planes.get(i).point.add(planes.get(j).point).multiply(0.5f);
						}
					}
					else {
						/* Plane.point is point on line of intersection between plane i and plane j. */
						final Double3D lineNormal = Utils.cross(crossProduct, planes.get(i).normal);
						plane.point = planes.get(i).point.add( lineNormal.multiply( planes.get(j).point.subtract(planes.get(i).point).dot(planes.get(j).normal)/lineNormal.dot(planes.get(j).normal)));
					}

					plane.normal = planes.get(j).normal.subtract(planes.get(i).normal).normalize();
					projPlanes.add(plane);
				}
				
				final Double3D tempResult = new Double3D(result);
				
				if (linearProgram3(projPlanes, radius, planes.get(i).normal, true, result) < projPlanes.size()) {
					/* This should in principle not happen.  The result is by definition already in the feasible region of this linear program. If it fails, it is due to small doubleing point error, and the current result is kept. */
					result.setTo(tempResult);
				}
				
				distance =  planes.get(i).normal.dot(planes.get(i).point.subtract(new Double3D(result)) );
			}
		}
	}
	
	

}

/**
 * \brief   Defines a directed line.
 */
class Line{
	
	/**
	 * \brief   The direction of the directed line.
	 */
	public Double3D direction;
	
	/**
	 * \brief   A point on the directed line.
	 */
	public Double3D point;
}