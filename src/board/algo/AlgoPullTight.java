/*
 *  Copyright (C) 2014  Alfons Wirtz  
 *   website www.freerouting.net
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License at <http://www.gnu.org/licenses/> 
 *   for more details.
 *
 * PullTightAlgo.java
 *
 * Created on 19. Juli 2003, 12:42
 */
package board.algo;

import java.util.Collection;
import java.util.Set;
import planar.PlaLineInt;
import planar.PlaPoint;
import planar.PlaPointFloat;
import planar.PlaPointInt;
import planar.PlaSide;
import planar.Polyline;
import planar.ShapeTile;
import planar.ShapeTileOctagon;
import autoroute.expand.ExpandCostFactor;
import board.RoutingBoard;
import board.items.BrdAbitPin;
import board.items.BrdAbitVia;
import board.items.BrdItem;
import board.items.BrdTrace;
import board.items.BrdTracePolyline;
import board.shape.ShapeSearchTree;
import board.shape.ShapeTreeObject;
import board.varie.BrdChangedArea;
import board.varie.BrdKeepPoint;
import board.varie.ItemFixState;
import board.varie.ItemSelectionChoice;
import board.varie.ItemSelectionFilter;
import board.varie.TraceAngleRestriction;
import datastructures.Signum;
import datastructures.ThreadStoppable;
import datastructures.TimeLimitStoppable;

/**
 * Class with functionality for optimizing traces and vias
 * Note that you cannot reuse this class after one optimization
 * MUST ger a new instance every time
 *
 * @author Alfons Wirtz
 */
public abstract class AlgoPullTight
   {
   private static final String classname="PullTightAlgo.";
   
   protected static final double c_max_cos_angle = 0.999;
   // with angles to close to 180 degree the algorithm becomes numerically unstable
   protected static final double c_min_corner_dist_square = 0.9;

   protected final RoutingBoard r_board;
   // If only_net_no > 0, only nets with this net numbers are optimized
   public final int[] only_net_no_arr;
   // If keep_point != null, traces containing the keep_point must also contain the keep_point after optimizing.
   private final BrdKeepPoint keep_point;
   // If stoppable_thread != null, the algorithm can be requested to be stopped.
   private final ThreadStoppable stoppable;

   protected int curr_layer;
   protected int curr_half_width;
   protected int[] curr_net_no_arr;
   protected int curr_cl_type;
   protected ShapeTileOctagon curr_clip_shape;
   protected Set<BrdAbitPin> contact_pins;
   protected int min_translate_dist;


   protected abstract Polyline pull_tight(Polyline p_polyline);

   protected abstract Polyline smoothen_start_corner_at_trace(BrdTracePolyline p_trace);

   protected abstract Polyline smoothen_end_corner_at_trace(BrdTracePolyline p_trace);

   /**
    * If p_only_net_no > 0, only traces with net number p_not_no are optimized. 
    */
   public static AlgoPullTight get_instance(RoutingBoard p_board, int[] p_only_net_no_arr, ShapeTileOctagon p_clip_shape, int p_min_translate_dist, ThreadStoppable p_stoppable, BrdKeepPoint p_keep_point)
      {
      TraceAngleRestriction angle_restriction = p_board.brd_rules.get_trace_snap_angle();

      if ( p_stoppable == null )
         {
         p_stoppable = new TimeLimitStoppable(20, null);
         p_board.userPrintln("null p_stoppable", new IllegalArgumentException("need to give me something"));
         }
      
      AlgoPullTight result;
      
      if (angle_restriction == TraceAngleRestriction.NINETY_DEGREE)
         {
         result = new AlgoPullTight90(p_board, p_only_net_no_arr, p_stoppable, p_keep_point );
         }
      else if (angle_restriction == TraceAngleRestriction.FORTYFIVE_DEGREE)
         {
         result = new AlgoPullTight45(p_board, p_only_net_no_arr, p_stoppable, p_keep_point );
         }
      else
         {
         result = new AlgoPullTightAny(p_board, p_only_net_no_arr, p_stoppable, p_keep_point );
         }
      
      result.curr_clip_shape = p_clip_shape;
      result.min_translate_dist = Math.max(p_min_translate_dist, 100);
      
      return result;
      }

   /**
    * Can only be created by subclasses
    */
   protected AlgoPullTight(RoutingBoard p_board, int[] p_only_net_no_arr, ThreadStoppable p_stoppable_thread, BrdKeepPoint p_keep_point)
      {
      r_board = p_board;
      only_net_no_arr = p_only_net_no_arr;
      stoppable = p_stoppable_thread;
      keep_point = p_keep_point;
      }

   /**
    * Now, if there is a stop I should cleanup properly, ok ?
    * @param p_trace_cost_arr
    * @param changed_area
    * @param layer_idx
    * @return true if something has changed
    */
   private boolean optimize_changed_area(ExpandCostFactor[] p_trace_cost_arr, BrdChangedArea changed_area, int layer_idx )
      {
      ShapeTileOctagon changed_region = changed_area.get_area(layer_idx);

      if (changed_region.is_empty()) return false;
      
      r_board.changed_area.set_empty(layer_idx);
      
      r_board.join_graphics_update_box(changed_region.bounding_box());
      
      double changed_area_offset = 1.5 * (r_board.brd_rules.clearance_matrix.max_value(layer_idx) + 2 * r_board.brd_rules.get_max_trace_half_width());
      
      changed_region = changed_region.enlarge(changed_area_offset);

      // search in the ShapeSearchTree for all overlapping traces with clip_shape on layer_idx
      Collection<ShapeTreeObject> items = r_board.overlapping_objects(changed_region, layer_idx);
   
      boolean something_changed = false;
      
      AlgoOptimizeVia optimize_via = new AlgoOptimizeVia(r_board);
      
      for ( ShapeTreeObject curr_ob : items )
         {
         if ( is_stop_requested()) break;
         
         if (curr_ob instanceof BrdTracePolyline)
            {
            BrdTracePolyline curr_trace = (BrdTracePolyline) curr_ob;
            if (curr_trace.pull_tight(this))
               {
               something_changed = true;
               
               if ( split_traces_at_keep_point()) break;
               }
            else if (smoothen_end_corners_at_trace_1(curr_trace))
               {
               something_changed = true;

               break; // because items may be removed
               }
            }
         else if (curr_ob instanceof BrdAbitVia && p_trace_cost_arr != null)
            {
            if (optimize_via.optimize_via_location( (BrdAbitVia) curr_ob, p_trace_cost_arr, min_translate_dist, 10))
               {
               something_changed = true;
               }
            }
         }
      
      return something_changed;
      }

   /**
    * returns true if something has changed
    * @param p_trace_cost_arr
    * @param changed_ares
    * @return
    */
   private boolean optimize_changed_area_changed(ExpandCostFactor[] p_trace_cost_arr, BrdChangedArea changed_area)
      {
      boolean something_changed = false;

      // starting with curr_min_translate_dist big is a try to avoid fine approximation at the beginning to avoid problems with dog ears
      
      for (int layer_idx = 0; layer_idx < r_board.get_layer_count(); ++layer_idx)
         {
         if ( is_stop_requested()) break;
         
         something_changed |= optimize_changed_area(p_trace_cost_arr, changed_area, layer_idx);
         }
      
      return something_changed;
      }
   
   
   /**
    * Function for optimizing the route in an internal marked area. 
    * If p_clip_shape != null, the optimizing area is restricted to p_clip_shape. 
    * p_trace_cost_arr is used for optimizing vias and may be null.
    */
   public void optimize_changed_area(ExpandCostFactor[] p_trace_cost_arr)
      {
      int counter=0;
      
      if (r_board.changed_area == null) return;
      
      while ( optimize_changed_area_changed(p_trace_cost_arr, r_board.changed_area) )
         {
         // The idea is that optimization goes on until there is a change

         counter++;

         if (is_stop_requested())
            {
            r_board.userPrintln(classname+"optimize_changed_area: STOP counter="+counter);
            break;
            }
         }
      
      }

   /**
    * Function for optimizing a single trace polygon p_contact_pins are the pins at the end corners of p_polyline. 
    * Other pins are regarded as obstacles, even if they are of the own net.
    */
   public Polyline pull_tight(Polyline p_polyline, int p_layer, int p_half_width, int[] p_net_no_arr, int p_cl_type, Set<BrdAbitPin> p_contact_pins)
      {
      curr_layer = p_layer;
      ShapeSearchTree search_tree = r_board.search_tree_manager.get_default_tree();
      curr_half_width = p_half_width + search_tree.get_clearance_compensation(p_cl_type, p_layer);
      curr_net_no_arr = p_net_no_arr;
      curr_cl_type = p_cl_type;
      contact_pins = p_contact_pins;
      return pull_tight(p_polyline);
      }

   /**
    * Terminates the pull tight algorithm as requested by stoppable
    */
   public final boolean is_stop_requested()
      {
      if (stoppable != null && stoppable.is_stop_requested())
         {
         r_board.userPrintln("PullTightAlgo.is_stop_requested");
         return true;
         }
      
      return false;
      }

   /**
    * tries to shorten p_polyline by relocating its lines
    */
   protected Polyline reposition_lines(Polyline p_polyline)
      {
      if (p_polyline.lines_arr.length < 5) return p_polyline;
      
      for (int index = 2; index < p_polyline.lines_arr.length - 2; ++index)
         {
         PlaLineInt new_line = reposition_line(p_polyline.lines_arr, index);

         if (new_line == null) continue;

         PlaLineInt[] line_arr = new PlaLineInt[p_polyline.lines_arr.length];
         System.arraycopy(p_polyline.lines_arr, 0, line_arr, 0, line_arr.length);
         line_arr[index] = new_line;
         
         Polyline result = new Polyline(line_arr);
         
         return skip_segments_of_length_0(result);
         }
      
      return p_polyline;
      }

   /**
    * Tries to reposition the line with index p_no to make the polyline consisting of p_line_arr shorter
    * @return null if it fails to shorten
    */
   protected PlaLineInt reposition_line(PlaLineInt[] p_line_arr, int p_no)
      {
      if (p_line_arr.length - p_no < 3) return null;

      if (curr_clip_shape != null)
         {
         // check, that the corners of the line to translate are inside the clip shape
         for (int index = -1; index < 1; ++index)
            {
            PlaPoint curr_corner = p_line_arr[p_no + index].intersection(p_line_arr[p_no + index + 1]);
            if (curr_clip_shape.is_outside(curr_corner))
               {
               return null;
               }
            }
         }
      
      PlaLineInt translate_line = p_line_arr[p_no];
      PlaPoint prev_corner = p_line_arr[p_no - 2].intersection(p_line_arr[p_no - 1]);
      PlaPoint next_corner = p_line_arr[p_no + 1].intersection(p_line_arr[p_no + 2]);
      
      double prev_dist = translate_line.signed_distance(prev_corner.to_float());
      double next_dist = translate_line.signed_distance(next_corner.to_float());
      
      if (Signum.of(prev_dist) != Signum.of(next_dist))
         {
         // the 2 corners are at different sides of translate_line
         return null;
         }
      
      PlaPoint nearest_point;
      double max_translate_dist;
      
      if (Math.abs(prev_dist) < Math.abs(next_dist))
         {
         nearest_point = prev_corner;
         max_translate_dist = prev_dist;
         }
      else
         {
         nearest_point = next_corner;
         max_translate_dist = next_dist;
         }
      
      double translate_dist = max_translate_dist;
      double delta_dist = max_translate_dist;
      PlaSide side_of_nearest_point = translate_line.side_of(nearest_point);
      int sign = Signum.as_int(max_translate_dist);
      PlaLineInt new_line = null;
      PlaLineInt[] check_lines = new PlaLineInt[3];
      check_lines[0] = p_line_arr[p_no - 1];
      check_lines[2] = p_line_arr[p_no + 1];
      boolean first_time = true;
      
      while (first_time || Math.abs(delta_dist) > min_translate_dist)
         {
         boolean check_ok = false;

         if (first_time && nearest_point instanceof PlaPointInt)
            {
            check_lines[1] = new PlaLineInt(nearest_point, translate_line.direction());
            }
         else
            {
            check_lines[1] = translate_line.translate(-translate_dist);
            }
         if (check_lines[1].equals(translate_line))
            {
            // may happen at first time if nearest_point is not an IntPoint
            return null;
            }
         
         PlaSide new_line_side_of_nearest_point = check_lines[1].side_of(nearest_point);
         if (new_line_side_of_nearest_point != side_of_nearest_point && new_line_side_of_nearest_point != PlaSide.COLLINEAR)
            {
            // moved a little bit to far at the first time because of numerical inaccuracy;
            // may happen if nearest_point is not an IntPoint
            double shorten_value = sign * 0.5;
            max_translate_dist -= shorten_value;
            translate_dist -= shorten_value;
            delta_dist -= shorten_value;
            continue;
            }
         
         Polyline tmp = new Polyline(check_lines);

         if (tmp.lines_arr.length == 3)
            {
            ShapeTile shape_to_check = tmp.offset_shape(curr_half_width, 0);
            check_ok = r_board.check_trace_shape(shape_to_check, curr_layer, curr_net_no_arr, curr_cl_type, this.contact_pins);

            }
         delta_dist /= 2;
         if (check_ok)
            {
            new_line = check_lines[1];
            if (first_time)
               {
               // biggest possible change
               break;
               }
            translate_dist += delta_dist;
            }
         else
            {
            translate_dist -= delta_dist;
            }
         first_time = false;
         }
      
      if (new_line != null && r_board.changed_area != null)
         {
         // mark the changed area
         PlaPointFloat afloat = check_lines[0].intersection_approx(new_line);
         if ( ! afloat.is_NaN() ) r_board.changed_area.join(afloat, curr_layer);
         
         afloat = check_lines[2].intersection_approx(new_line);
         if ( ! afloat.is_NaN() ) r_board.changed_area.join(afloat, curr_layer);
         
         afloat = p_line_arr[p_no - 1].intersection_approx(p_line_arr[p_no]);
         if ( ! afloat.is_NaN() ) r_board.changed_area.join(afloat, curr_layer);
         
         afloat = p_line_arr[p_no].intersection_approx(p_line_arr[p_no + 1]);
         if ( ! afloat.is_NaN() ) r_board.changed_area.join(afloat, curr_layer);
         }

      return new_line;
      }

   /**
    * tries to skip line segments of length 0. A check is necessary before skipping because new dog ears may occur.
    */
   protected Polyline skip_segments_of_length_0(Polyline p_polyline)
      {
      boolean polyline_changed = false;
      
      Polyline curr_polyline = p_polyline;
      
      for (int index = 1; index < curr_polyline.lines_arr.length - 1; index++)
         {
         boolean try_skip;
         if (index == 1 || index == curr_polyline.lines_arr.length - 2)
            {
            // the position of the first corner and the last corner must be retained exactly
            PlaPoint prev_corner = curr_polyline.corner(index - 1);
            PlaPoint curr_corner = curr_polyline.corner(index);
            try_skip = curr_corner.equals(prev_corner);
            }
         else
            {
            PlaPointFloat prev_corner = curr_polyline.corner_approx(index - 1);
            PlaPointFloat curr_corner = curr_polyline.corner_approx(index);
            try_skip = curr_corner.distance_square(prev_corner) < c_min_corner_dist_square;
            }

         if (try_skip)
            {
            // check, if skipping the line of length 0 does not
            // result in a clearance violation
            PlaLineInt[] curr_lines = new PlaLineInt[curr_polyline.lines_arr.length - 1];
            System.arraycopy(curr_polyline.lines_arr, 0, curr_lines, 0, index);
            System.arraycopy(curr_polyline.lines_arr, index + 1, curr_lines, index, curr_lines.length - index);
            Polyline tmp = new Polyline(curr_lines);
            boolean check_ok = (tmp.lines_arr.length == curr_lines.length);
            if (check_ok && !curr_polyline.lines_arr[index].is_multiple_of_45_degree())
               {
               // no check necessary for skipping 45 degree lines, because the check is performance critical and the line shapes
               // are intersected with the bounding octagon anyway.
               if (index > 1)
                  {
                  ShapeTile shape_to_check = tmp.offset_shape(curr_half_width, index - 2);
                  check_ok = r_board.check_trace_shape(shape_to_check, curr_layer, curr_net_no_arr, curr_cl_type, contact_pins);
                  }
               if (check_ok && (index < curr_polyline.lines_arr.length - 2))
                  {
                  ShapeTile shape_to_check = tmp.offset_shape(curr_half_width, index - 1);
                  check_ok = r_board.check_trace_shape(shape_to_check, curr_layer, curr_net_no_arr, curr_cl_type, contact_pins);
                  }
               }
            if (check_ok)
               {
               polyline_changed = true;
               curr_polyline = tmp;
               --index;
               }
            }
         }
      
      if (!polyline_changed)
         {
         return p_polyline;
         }
      return curr_polyline;
      }

   /**
    * Smoothen acute angles with contact traces. Returns true, if something was changed
    * @return true if something was changed
    */
   public boolean smoothen_end_corners_at_trace(BrdTracePolyline p_trace)
      {
      curr_layer = p_trace.get_layer();
      curr_half_width = p_trace.get_half_width();
      curr_net_no_arr = p_trace.net_no_arr;
      curr_cl_type = p_trace.clearance_class_no();
      
      return smoothen_end_corners_at_trace_1(p_trace);
      }

   /**
    * Smoothen acute angles with contact traces. 
    * @return true, if something was changed.
    */
   private boolean smoothen_end_corners_at_trace_1(BrdTracePolyline p_trace)
      {
      // try to improve the connection to other traces
      if (p_trace.is_shove_fixed()) return false;
      
      Set<BrdAbitPin> saved_contact_pins = contact_pins;
      // to allow the trace to slide to the end point of a contact trace, if the contact trace ends at a pin.
      contact_pins = null;
      boolean result = false;
      boolean connection_to_trace_improved = true;
      
      BrdTracePolyline curr_trace = p_trace;
      
      while (connection_to_trace_improved)
         {
         connection_to_trace_improved = false;
         Polyline adjusted_polyline = smoothen_end_corners_at_trace_2(curr_trace);
         if (adjusted_polyline != null)
            {
            result = true;
            connection_to_trace_improved = true;
            int trace_layer = curr_trace.get_layer();
            int curr_cl_class = curr_trace.clearance_class_no();
            ItemFixState curr_fixed_state = curr_trace.get_fixed_state();
            r_board.remove_item(curr_trace);
            curr_trace = r_board.insert_trace_without_cleaning(adjusted_polyline, trace_layer, curr_half_width, curr_trace.net_no_arr, curr_cl_class, curr_fixed_state);
            for (int curr_net_no : curr_trace.net_no_arr)
               {
               r_board.split_traces(adjusted_polyline.first_corner(), trace_layer, curr_net_no);
               r_board.split_traces(adjusted_polyline.last_corner(), trace_layer, curr_net_no);
               r_board.normalize_traces(curr_net_no);

               if (split_traces_at_keep_point())
                  {
                  return true;
                  }
               }
            }
         }
      
      contact_pins = saved_contact_pins;
      
      return result;
      }

   /**
    * Splits the traces containing this.keep_point if this.keep_point != null
    * TODO this does not actually split the trace, just test if it can be split, right ?
    * @return true, if something was split.
    */
   public boolean split_traces_at_keep_point()
      {
      // if no keep point defined
      if ( keep_point == null) return false;

      ItemSelectionFilter filter = new ItemSelectionFilter(ItemSelectionChoice.TRACES);
      
      Collection<BrdItem> picked_items = r_board.pick_items( keep_point.keep_point,  keep_point.on_layer, filter);
      
      for (BrdItem curr_item : picked_items)
         {
         BrdTrace[] split_pieces = ((BrdTrace) curr_item).split( keep_point.keep_point);

         if (split_pieces != null) return true;
         }

      return false;
      }

   /**
    * Smoothen acute angles with contact traces. 
    * @return null if nothing was changed.
    */
   private Polyline smoothen_end_corners_at_trace_2(BrdTracePolyline p_trace)
      {
      if (p_trace == null || !p_trace.is_on_the_board()) return null;
      
      Polyline result = smoothen_start_corner_at_trace(p_trace);

      if (result == null)
         {
         result = smoothen_end_corner_at_trace(p_trace);
         if (result != null && r_board.changed_area != null)
            {
            // mark the changed area
            r_board.changed_area.join(result.corner_approx(result.corner_count() - 1), curr_layer);
            }
         }
      else if (r_board.changed_area != null)
         {
         // mark the changed area
         r_board.changed_area.join(result.corner_approx(0), curr_layer);
         }
      
      if (result != null)
         {
         contact_pins = p_trace.touching_pins_at_end_corners();
         result = skip_segments_of_length_0(result);
         }
      
      return result;
      }

   boolean acid_trap_skip = true;  // damiano, possibly try it out in the future
   
   /**
    * Wraps around pins of the own net to avoid acid traps.
    */
   protected Polyline acid_traps_wrap_around(Polyline p_polyline)
      {
      if (acid_trap_skip) return p_polyline;
      
      Polyline result = p_polyline;

      Polyline new_polyline = r_board.shove_trace_algo.spring_over_obstacles(p_polyline, curr_half_width, curr_layer, curr_net_no_arr, curr_cl_type, contact_pins);
      
      if (new_polyline != null && new_polyline != p_polyline)
         {
         if (r_board.check_polyline_trace(new_polyline, curr_layer, curr_half_width, curr_net_no_arr, curr_cl_type))
            {
            result = new_polyline;
            }
         }
      return result;
      }
   
   
   
   }