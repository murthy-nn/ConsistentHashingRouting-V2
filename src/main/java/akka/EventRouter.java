package akka;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.GroupRouter;
import akka.actor.typed.javadsl.PoolRouter;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.Routers;
import akka.actor.typed.receptionist.Receptionist;
import akka.actor.typed.receptionist.ServiceKey;
import model.EventCommand;
import model.Device;

public class EventRouter extends AbstractBehavior<EventCommand> {	
	int poolSize = 5;
	private ActorRef<EventCommand> worker;

	private EventRouter(ActorContext<EventCommand> context) {
		super(context);
		getContext().getLog().debug("constructor");
		groupRouter_v2c(context);
		//poolRouter(context);
	}

	void groupRouter_v2a (ActorContext<EventCommand> context) {
		//Very close to StackOverFlow response
		/* Getting following exception.
		 * java.lang.IllegalStateException: Actor [Actor[akka://MyApp/user/EventWorker-2#-1534363645]] 
		 * of AbstractBehavior class [akka.EventRouter] was created with wrong ActorContext 
		 * [Actor[akka://MyApp/user#0]]. 
		 * Wrap in Behaviors.setup and pass the context to the constructor of AbstractBehavior.
		 * 
		 * akka.actor.LocalActorRef - Message [akka.EventWorker$StopEventProcessing] to 
		 * Actor[akka://MyApp/user/EventWorker-2#-1534363645] was not delivered. 
		 * [1] dead letters encountered. If this is not an expected behavior then 
		 * Actor[akka://MyApp/user/EventWorker-2#-1534363645] may have terminated unexpectedly.
		 */
		getContext().getLog().debug("groupRouter_v2a");
		getContext().getLog().debug("Event ServiceKey created");
		ServiceKey<EventCommand> serviceKey = ServiceKey.create(EventCommand.class, "Event-sk");

		getContext().getLog().debug("Event router created with poolSize of " + poolSize);
		GroupRouter<EventCommand> router = Routers.group(serviceKey).
				withConsistentHashingRouting(poolSize, msg -> msg.getDevice().getIp());

		getContext().getLog().debug("Event worker is registered with the Receptionist");
		Behavior<EventCommand> workerBehavior = Behaviors.setup(
				ctx -> {
					ctx.getSystem().receptionist().tell(Receptionist.register(serviceKey, ctx.getSelf()));
					return this;
					//return Behaviors.same();
					//return Behaviors.empty();
				});
		for (int i=1; i<=2; i++) {
			worker = getContext().spawn(workerBehavior, "EventWorker-" + i);
		}		
	}

	void groupRouter_v2b (ActorContext<EventCommand> context) {
		//Based on StackOverFlow response
		getContext().getLog().debug("groupRouter_v2");
		getContext().getLog().debug("Event ServiceKey created");
		ServiceKey<EventCommand> serviceKey = ServiceKey.create(EventCommand.class, "Event-sk");

		getContext().getLog().debug("Event router created with poolSize of " + poolSize);
		GroupRouter<EventCommand> router = Routers.group(serviceKey).
				withConsistentHashingRouting(poolSize, msg -> msg.getDevice().getIp());

		getContext().getLog().debug("Event worker is registered with the Receptionist");
		for (int i=1; i<=5; i++) {
			/* v2b vs v2c: While spawning, workers are associated with the router. This is similar to 
			 * the design followed in Pool Router (poolRouter method).
			 */
			worker = getContext().spawn(router, "EventWorker-"+i);
			context.getSystem().receptionist().tell(Receptionist.register(serviceKey, worker));
		}		
	}
	
	void groupRouter_v2c (ActorContext<EventCommand> context) {
		//Based on StackOverFlow response
		/* groupRouter_v2b vs groupRouter_v2c: different 
		 * msg.getDevice().getIp() vs msg.getHash(msg.getDevice().getId())
		 * 
		 */
		getContext().getLog().debug("groupRouter_v2b");
		getContext().getLog().debug("Event ServiceKey created");
		ServiceKey<EventCommand> serviceKey = ServiceKey.create(EventCommand.class, "Event-sk");

		getContext().getLog().debug("Event router created with poolSize of " + poolSize);

		/* TODO: router is unused. As a result, I do not see the workers are associated with the
		 * groupRouter. But, still StartEventProcessing Events reach the EventWorker. How come?
		 * But, StopEventProcessing do not reach the EventWorker
		 */
		GroupRouter<EventCommand> router = Routers.group(serviceKey).
				withConsistentHashingRouting(poolSize, msg -> msg.getDevice().getIp());
				//withConsistentHashingRouting(poolSize, msg -> msg.getHash(msg.getDevice().getId()));

		getContext().getLog().debug("Event worker is registered with the Receptionist");
		Behavior<EventCommand> workerBehavior = 
				Behaviors.supervise(EventWorker.create()).onFailure(SupervisorStrategy.restart());
		for (int i=1; i<=2; i++) {
			worker = getContext().spawn(workerBehavior, "EventWorker-" + i);
			context.getSystem().receptionist().tell(Receptionist.register(serviceKey, worker));
		}		
	}

	void poolRouter(ActorContext<EventCommand> context) {
		//Pool router approach
		getContext().getLog().debug("poolRouter");
		getContext().getLog().debug("Event router created with poolSize of " + poolSize);
		PoolRouter<EventCommand> router = Routers.pool(
				poolSize,
				Behaviors.supervise(EventWorker.create()).onFailure(SupervisorStrategy.restart())
				);
		worker = getContext().spawn(router, "EventWorker");
	}

	public static Behavior<EventCommand> create() {
		return Behaviors.setup(EventRouter::new);
	}

	@Override
	public Receive<EventCommand> createReceive() {
		getContext().getLog().debug("createReceive");
		return newReceiveBuilder()
				.onMessage(StartEventProcessing.class, this::onStartEventProcessing)
				.onMessage(StopEventProcessing.class, this::onStopEventProcessing)
				.build();
	}

	private Behavior<EventCommand> onStartEventProcessing(StartEventProcessing command) {
		getContext().getLog().debug("onStartEventProcessing. worker=" + worker + 
				", Device=" + command.getDevice().getId());
		worker.tell(new EventWorker.StartEventProcessing(command.getDevice()));
		return this;
	}

	private Behavior<EventCommand> onStopEventProcessing(StopEventProcessing command) {
		/* Route onStopEventProcessing message to an existing EventWorker based on  DeviceIP
		 */
		getContext().getLog().debug("onStopEventProcessing. worker=" + worker + 
				", Device=" + command.getDevice().getId());
		worker.tell(new EventWorker.StopEventProcessing(command.getDevice()));
		return this;
	}

	/******** Message or command definition ********/
	public static class StartEventProcessing extends EventCommand {
		private static final long serialVersionUID = 1L;
		public StartEventProcessing(Device device) {
			super(device);
		}
		public static long getSerialversionuid() {
			return serialVersionUID;
		}
	}

	public static class StopEventProcessing extends EventCommand {
		/* In reality, this message would have additional parameters such as 
		 * Session details in comparison to StartEventProcessing.
		 */
		private static final long serialVersionUID = 1L;
		public StopEventProcessing(Device device) {
			super(device);
		}
		public static long getSerialversionuid() {
			return serialVersionUID;
		}
	}
}
