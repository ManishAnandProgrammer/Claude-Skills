/**
 * Plans: days a user sets aside for one of their todos (the example piece). It shows two shapes:
 *
 * <ul>
 *   <li><b>A parent in another module, referenced by id:</b> a plan's {@code todo_id} is a UUID column
 *       with a database foreign key, not an entity relation. The todo is reached through the todo
 *       module's facade ({@code todo.api.Todos}), never its repository or entity: the create and the
 *       reschedule lock it ({@code getOwnedTodoViewForUpdate}), because a plan is only for an open todo; the reads take an
 *       unlocked view, and a todo that vanished between their two statements is the plan's own 404.</li>
 *   <li><b>No overlapping ranges:</b> one owner's plans never share a day, guaranteed by an exclusion
 *       constraint ({@code ex_plans_owner_id_dates}, changeset 0011), mapped to 409
 *       {@code PLAN_OVERLAPS}; never a check-then-insert, which two concurrent creates both pass.</li>
 * </ul>
 */
package com.example.app.planning;
